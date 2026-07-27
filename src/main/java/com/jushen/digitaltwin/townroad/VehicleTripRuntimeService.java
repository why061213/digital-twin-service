package com.jushen.digitaltwin.townroad;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jushen.digitaltwin.baidu.RoutePlanningService;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 将同一车辆在当前订单快照中的多张订单组装成一个运输任务簇。
 *
 * <p>该层不推测固定的提卸货顺序，只维护“待提货、已装车待卸货、已完成”三个集合。
 * 下游旧协议仍只接收一个稳定锚点订单，但锚点不再代表整车只有这一张订单。</p>
 */
@Service
public class VehicleTripRuntimeService {
    private static final Logger log = LoggerFactory.getLogger(VehicleTripRuntimeService.class);
    private static final long SAME_SNAPSHOT_CANDIDATE_WINDOW_MS = 30L * 60L * 1000L;
    private static final double MAX_INSERTION_DETOUR_KM = 30d;
    private static final double MAX_INSERTION_DETOUR_RATIO = 0.20d;
    private static final double DIRECTION_TOLERANCE_KM = 10d;
    private static final long MIN_VALID_DWELL_MS = 60_000L;
    private final VehicleOrderChainStore orderStore;
    private final VehicleTripTopologyService topologyService;
    private final RoutePlanningService routePlanningService;
    private final ObjectMapper objectMapper;
    private final Path tripsRoot;
    private final Set<String> restoreAttempted = new LinkedHashSet<>();
    private final Map<String, VehicleTripRuntime> currentByVehicle = new LinkedHashMap<>();
    private final Map<String, StopPresenceEvidence> stopPresenceByTripStop = new LinkedHashMap<>();

    @Autowired
    public VehicleTripRuntimeService(
            VehicleOrderChainStore orderStore,
            VehicleTripTopologyService topologyService,
            ObjectMapper objectMapper,
            RoutePlanningService routePlanningService
    ) {
        this.orderStore = orderStore;
        this.topologyService = topologyService;
        this.objectMapper = objectMapper;
        this.routePlanningService = routePlanningService;
        this.tripsRoot = orderStore.runtimeRootPath().resolve("trips");
    }

    VehicleTripRuntimeService(
            VehicleOrderChainStore orderStore,
            VehicleTripTopologyService topologyService,
            ObjectMapper objectMapper
    ) {
        this(orderStore, topologyService, objectMapper, null);
    }

    VehicleTripRuntimeService(VehicleOrderChainStore orderStore) {
        this.orderStore = orderStore;
        this.topologyService = new VehicleTripTopologyService();
        this.objectMapper = null;
        this.routePlanningService = null;
        this.tripsRoot = null;
    }

    VehicleTripRuntimeService(
            VehicleOrderChainStore orderStore,
            VehicleTripTopologyService topologyService,
            RoutePlanningService routePlanningService
    ) {
        this.orderStore = orderStore;
        this.topologyService = topologyService;
        this.routePlanningService = routePlanningService;
        this.objectMapper = null;
        this.tripsRoot = null;
    }

    public synchronized List<VehicleTripRuntime> reconcile(
            List<VehicleOrderChainStore.StoredOrder> history
    ) {
        Map<String, List<VehicleOrderChainStore.StoredOrder>> byVehicle = new LinkedHashMap<>();
        for (VehicleOrderChainStore.StoredOrder stored : history == null ? List.<VehicleOrderChainStore.StoredOrder>of() : history) {
            if (stored == null || stored.record() == null) continue;
            byVehicle.computeIfAbsent(stored.vehicleKey(), ignored -> new ArrayList<>()).add(stored);
        }

        Map<String, VehicleTripRuntime> next = new LinkedHashMap<>();
        for (Map.Entry<String, List<VehicleOrderChainStore.StoredOrder>> entry : byVehicle.entrySet()) {
            VehicleTripRuntime previous = previousRuntime(entry.getKey());
            List<VehicleOrderChainStore.StoredOrder> orders = latestOrderInstances(entry.getValue());
            List<VehicleOrderChainStore.StoredOrder> unfinished = orders.stream()
                    .filter(order -> !isCompleted(order.record().status()))
                    .toList();
            List<VehicleOrderChainStore.StoredOrder> members;
            if (!unfinished.isEmpty()) {
                // 已经属于同一运行态的订单变成完成后仍留在本趟；冷启动时不把历史完成单
                // 直接拼入新任务，避免“上一趟已完成 + 下一趟待装载”被误并。
                Set<String> previousMembers = previousOrderIds(entry.getKey());
                members = orders.stream()
                        .filter(order -> !isCompleted(order.record().status())
                                || previousMembers.contains(order.key()))
                        .toList();
            } else {
                VehicleOrderChainStore.StoredOrder latestCompleted = orders.stream()
                        .max(Comparator.comparingLong(VehicleOrderChainStore.StoredOrder::lastObservedAtMs))
                        .orElse(null);
                members = latestCompleted == null ? List.of() : List.of(latestCompleted);
            }
            if (members.isEmpty()) continue;

            VehicleTripRuntime runtime = build(entry.getKey(), members, previous);
            runtime = withTopology(runtime, runtime.topology());
            next.put(entry.getKey(), runtime);
            persist(runtime);
        }
        currentByVehicle.clear();
        currentByVehicle.putAll(next);
        return List.copyOf(currentByVehicle.values());
    }

    public synchronized List<VehicleTripRuntime> currentTrips() {
        return List.copyOf(currentByVehicle.values());
    }

    public VehicleTripTopologyService.TripStop currentTargetStop(VehicleTripRuntime trip) {
        if (trip == null || trip.topology() == null) return null;
        VehicleTripTopologyService.TripLeg leg = trip.topology().legs().stream()
                .filter(candidate -> candidate.legId().equals(trip.currentLegId()))
                .findFirst().orElse(null);
        String stopId = leg == null
                ? trip.topology().plannedStopIds().stream().findFirst().orElse(null)
                : leg.toStopId();
        return topologyService.stopById(trip.topology(), stopId);
    }

    public synchronized TargetPresenceObservation observeCurrentTarget(
            VehicleTripRuntime trip,
            double[] position,
            Instant observedAt
    ) {
        VehicleTripTopologyService.TripStop target = currentTargetStop(trip);
        if (target == null || !hasCoordinates(position) || !hasCoordinates(target.coordinates())) {
            return new TargetPresenceObservation(TargetPresenceState.EN_ROUTE, target, 0d, 0, 0L);
        }
        double distanceKm = VehicleTripTopologyService.haversineKm(position, target.coordinates());
        String key = trip.tripId() + "|" + target.stopId();
        long at = (observedAt == null ? Instant.now() : observedAt).toEpochMilli();
        StopPresenceEvidence previous = stopPresenceByTripStop.get(key);
        if (distanceKm <= VehicleTripTopologyService.DEFAULT_NODE_MERGE_RADIUS_KM) {
            StopPresenceEvidence evidence = previous == null
                    ? new StopPresenceEvidence(at, at, 1, false)
                    : new StopPresenceEvidence(previous.enteredAtMs(), at, previous.sampleCount() + 1,
                    previous.dwellConfirmed()
                            || (previous.sampleCount() + 1 >= 2 && at - previous.enteredAtMs() >= MIN_VALID_DWELL_MS));
            stopPresenceByTripStop.put(key, evidence);
            TargetPresenceState state = evidence.dwellConfirmed()
                    ? TargetPresenceState.DWELLING : TargetPresenceState.ARRIVED;
            return new TargetPresenceObservation(
                    state, target, distanceKm, evidence.sampleCount(), at - evidence.enteredAtMs());
        }
        stopPresenceByTripStop.remove(key);
        if (previous != null && previous.dwellConfirmed()) {
            return new TargetPresenceObservation(TargetPresenceState.DEPARTED, target, distanceKm,
                    previous.sampleCount(), previous.lastObservedAtMs() - previous.enteredAtMs());
        }
        return new TargetPresenceObservation(
                previous == null ? TargetPresenceState.EN_ROUTE : TargetPresenceState.PASSING_BY,
                target, distanceKm, previous == null ? 0 : previous.sampleCount(),
                previous == null ? 0L : previous.lastObservedAtMs() - previous.enteredAtMs());
    }

    /** 用当前位置评估候选/排队订单是否适合插入当前剩余路线；只重排尚未执行的后缀。 */
    public synchronized VehicleTripRuntime evaluateDynamicInsertions(
            VehicleTripRuntime trip,
            double[] currentPosition
    ) {
        if (trip == null || currentPosition == null || currentPosition.length < 2) return trip;
        VehicleTripTopologyService.TripTopology baselineTopology = topologyService.build(
                trip.ordersByInstanceId(), trip.orderMembers(), trip.onboardOrderIds(),
                trip.completedOrderIds(), trip.topology(), currentPosition, null);
        double baselineKm = topologyDistanceKm(baselineTopology);
        if (!Double.isFinite(baselineKm)) return trip;
        String selectedOrderId = null;
        double selectedExtraKm = Double.MAX_VALUE;
        VehicleTripTopologyService.TripTopology selectedTopology = null;
        for (Map.Entry<String, TripMemberState> entry : trip.orderMembers().entrySet()) {
            if (entry.getValue() != TripMemberState.CANDIDATE && entry.getValue() != TripMemberState.QUEUED) continue;
            VehicleOrderChainStore.StoredOrder candidate = trip.ordersByInstanceId().get(entry.getKey());
            double[] pickup = candidate == null || candidate.record().from() == null
                    ? null : candidate.record().from().coords();
            double[] delivery = candidate == null || candidate.record().to() == null
                    ? null : candidate.record().to().coords();
            if (!hasCoordinates(pickup) || !hasCoordinates(delivery) || !validCargo(candidate.record())) continue;
            Map<String, TripMemberState> candidateMembers = new LinkedHashMap<>(trip.orderMembers());
            candidateMembers.put(entry.getKey(), TripMemberState.CONFIRMED);
            VehicleTripTopologyService.TripTopology candidateTopology = topologyService.build(
                    trip.ordersByInstanceId(), Map.copyOf(candidateMembers), trip.onboardOrderIds(),
                    trip.completedOrderIds(), trip.topology(), currentPosition, null);
            if (!respectsTimeWindows(candidateTopology)) continue;
            double extraKm = topologyDistanceKm(candidateTopology) - baselineKm;
            double allowedDetourKm = Math.max(MAX_INSERTION_DETOUR_KM, baselineKm * MAX_INSERTION_DETOUR_RATIO);
            double pickupDistanceKm = VehicleTripTopologyService.haversineKm(currentPosition, pickup);
            boolean directionValid = pickupDistanceKm <= baselineKm + DIRECTION_TOLERANCE_KM;
            if (directionValid && extraKm <= allowedDetourKm && extraKm < selectedExtraKm) {
                selectedOrderId = entry.getKey();
                selectedExtraKm = extraKm;
                selectedTopology = candidateTopology;
            }
        }
        if (selectedOrderId == null || selectedTopology == null) return trip;

        Map<String, TripMemberState> members = new LinkedHashMap<>(trip.orderMembers());
        members.put(selectedOrderId, TripMemberState.CONFIRMED);
        Set<String> queued = new LinkedHashSet<>(trip.queuedOrderIds());
        queued.remove(selectedOrderId);
        Set<String> pendingPickup = new LinkedHashSet<>(trip.pendingPickupOrderIds());
        pendingPickup.add(selectedOrderId);
        List<String> activeOrders = new ArrayList<>(trip.orderInstanceIds());
        if (!activeOrders.contains(selectedOrderId)) activeOrders.add(selectedOrderId);
        VehicleTripTopologyService.TripTopology topology = selectedTopology;
        if (routePlanningService != null && !topology.legs().isEmpty()) {
            PlannedTopology baselineRoad = planTopology(baselineTopology);
            PlannedTopology insertedRoad = planTopology(topology);
            if (baselineRoad.success() && insertedRoad.success()) {
                double roadExtraKm = insertedRoad.distanceKm() - baselineRoad.distanceKm();
                double roadAllowedKm = Math.max(MAX_INSERTION_DETOUR_KM,
                        baselineRoad.distanceKm() * MAX_INSERTION_DETOUR_RATIO);
                if (roadExtraKm > roadAllowedKm) return trip;
                topology = insertedRoad.topology();
                selectedExtraKm = roadExtraKm;
            }
        }
        VehicleTripRuntime updated = new VehicleTripRuntime(
                trip.tripId(), trip.vehicleKey(), List.copyOf(activeOrders), trip.ordersByInstanceId(),
                Map.copyOf(members), Set.copyOf(queued), trip.rejectedOrderIds(),
                trip.visitedPickupOrderIds(), trip.onboardOrderIds(), Set.copyOf(pendingPickup),
                trip.pendingDeliveryOrderIds(), trip.completedOrderIds(), List.copyOf(pendingPickup),
                trip.anchorOrderInstanceId(), TripPhase.COLLECTING, trip.openedAt(), trip.closedAt(),
                trip.runtimeLineId(), null, null, topology.planVersion(), PositionQuality.FRESH, topology);
        updated = withTopology(updated, topology);
        currentByVehicle.put(updated.vehicleKey(), updated);
        persist(updated);
        log.info("[VehicleTrip] inserted along route: trip={}, order={}, extraKm={}",
                trip.tripId(), selectedOrderId, String.format(Locale.ROOT, "%.2f", selectedExtraKm));
        return updated;
    }

    private double topologyDistanceKm(VehicleTripTopologyService.TripTopology topology) {
        if (topology == null || topology.legs() == null) return Double.NaN;
        return topology.legs().stream().mapToDouble(VehicleTripTopologyService.TripLeg::distanceKm).sum();
    }

    private boolean validCargo(ExternalOrderRecord record) {
        if (record == null || record.vehicle() == null || record.vehicle().cargoWeight() == null) return true;
        double weight = record.vehicle().cargoWeight();
        return Double.isFinite(weight) && weight >= 0d;
    }

    private boolean respectsTimeWindows(VehicleTripTopologyService.TripTopology topology) {
        // 上游当前没有结构化时间窗字段；一旦 TripStop.timeWindow 有值，未知格式不冒险接单。
        return topology.stops().stream().allMatch(stop -> stop.timeWindow() == null || stop.timeWindow().isBlank());
    }

    private PlannedTopology planTopology(VehicleTripTopologyService.TripTopology topology) {
        Map<String, VehicleTripTopologyService.TripStop> stops = new LinkedHashMap<>();
        for (VehicleTripTopologyService.TripStop stop : topology.stops()) stops.put(stop.stopId(), stop);
        List<VehicleTripTopologyService.TripLeg> plannedLegs = new ArrayList<>();
        double totalKm = 0d;
        for (VehicleTripTopologyService.TripLeg leg : topology.legs()) {
            double[] from = "CURRENT_POSITION".equals(leg.fromStopId()) && !leg.coordinates().isEmpty()
                    ? leg.coordinates().get(0)
                    : stops.get(leg.fromStopId()) == null ? null : stops.get(leg.fromStopId()).coordinates();
            double[] to = stops.get(leg.toStopId()) == null ? null : stops.get(leg.toStopId()).coordinates();
            if (!hasCoordinates(from) || !hasCoordinates(to)) return new PlannedTopology(false, topology, 0d);
            RoutePlanningService.PlannedRoute route = routePlanningService.plan(from, to);
            if (!route.success()) return new PlannedTopology(false, topology, 0d);
            List<double[]> coordinates = route.matchingCoordinates() == null || route.matchingCoordinates().isEmpty()
                    ? route.coordinates() : route.matchingCoordinates();
            plannedLegs.add(new VehicleTripTopologyService.TripLeg(
                    leg.legId(), leg.fromStopId(), leg.toStopId(), leg.purpose(), coordinates,
                    route.distanceKm(), route.durationMs(), leg.state(), leg.segmentKey()));
            totalKm += route.distanceKm();
        }
        return new PlannedTopology(true, new VehicleTripTopologyService.TripTopology(
                topology.stops(), topology.nodes(), topology.plannedStopIds(), List.copyOf(plannedLegs),
                topology.planVersion(), topology.planSignature()), totalKm);
    }

    private boolean hasCoordinates(double[] coordinates) {
        return coordinates != null && coordinates.length >= 2
                && Double.isFinite(coordinates[0]) && Double.isFinite(coordinates[1]);
    }

    private record PlannedTopology(
            boolean success,
            VehicleTripTopologyService.TripTopology topology,
            double distanceKm
    ) {}

    /** 将实时位置/轨迹证据回写到运行态；不改写上游订单，也不伪造固定节点顺序。 */
    public synchronized VehicleTripRuntime applyEligibilityEvidence(
            VehicleTripRuntime trip,
            String decision
    ) {
        VehicleTripTopologyService.TripStop target = currentTargetStop(trip);
        String orderInstanceId = target == null ? trip.anchorOrderInstanceId() : target.orderInstanceId();
        String stopId = target == null ? orderInstanceId + "::PICKUP" : target.stopId();
        return applyEligibilityEvidence(trip, stopId, orderInstanceId, decision, null);
    }

    public synchronized VehicleTripRuntime applyEligibilityEvidence(
            VehicleTripRuntime trip,
            String stopId,
            String orderInstanceId,
            String decision,
            double[] position
    ) {
        if (trip == null || decision == null) return trip;
        VehicleTripTopologyService.TripStop evidenceStop = topologyService.stopById(trip.topology(), stopId);
        if (evidenceStop != null && !evidenceStop.orderInstanceId().equals(orderInstanceId)) return trip;
        TripPhase phase = trip.phase();
        Set<String> visited = new LinkedHashSet<>(trip.visitedPickupOrderIds());
        Set<String> onboard = new LinkedHashSet<>(trip.onboardOrderIds());
        Set<String> pendingPickup = new LinkedHashSet<>(trip.pendingPickupOrderIds());
        Set<String> pendingDelivery = new LinkedHashSet<>(trip.pendingDeliveryOrderIds());
        PositionQuality positionQuality = trip.positionQuality();
        String currentNodeId = trip.currentNodeId();
        if ("NO_REAL_POSITION".equals(decision)) {
            positionQuality = positionQuality == PositionQuality.UNKNOWN
                    ? PositionQuality.UNKNOWN : PositionQuality.STALE;
        } else {
            positionQuality = PositionQuality.FRESH;
        }
        VehicleTripTopologyService.TripTopology evidenceTopology = trip.topology();
        boolean planChanged = false;
        if ("ARRIVED".equals(decision)) {
            currentNodeId = nodeForStop(evidenceTopology, stopId);
            evidenceTopology = topologyService.withVisitState(
                    evidenceTopology, stopId, VehicleTripTopologyService.VisitState.ARRIVED);
        } else if ("LOADING".equals(decision) || "UNLOADING".equals(decision)) {
            currentNodeId = nodeForStop(evidenceTopology, stopId);
            phase = evidenceStop == null || evidenceStop.action() == VehicleTripTopologyService.StopAction.PICKUP
                    ? TripPhase.AT_PICKUP : TripPhase.DISTRIBUTING;
            evidenceTopology = topologyService.withVisitState(
                    evidenceTopology, stopId, VehicleTripTopologyService.VisitState.DWELLING);
        } else if ("DEPARTED".equals(decision)) {
            planChanged = true;
            if (evidenceStop == null || evidenceStop.action() == VehicleTripTopologyService.StopAction.PICKUP) {
                pendingPickup.remove(orderInstanceId);
                visited.add(orderInstanceId);
                onboard.add(orderInstanceId);
                pendingDelivery.add(orderInstanceId);
                phase = pendingPickup.isEmpty() ? TripPhase.LINEHAUL : TripPhase.COLLECTING;
            } else {
                onboard.remove(orderInstanceId);
                pendingDelivery.remove(orderInstanceId);
                Set<String> completed = new LinkedHashSet<>(trip.completedOrderIds());
                completed.add(orderInstanceId);
                trip = new VehicleTripRuntime(
                        trip.tripId(), trip.vehicleKey(), trip.orderInstanceIds(), trip.ordersByInstanceId(),
                        trip.orderMembers(), trip.queuedOrderIds(), trip.rejectedOrderIds(),
                        trip.visitedPickupOrderIds(), trip.onboardOrderIds(), trip.pendingPickupOrderIds(),
                        trip.pendingDeliveryOrderIds(), Set.copyOf(completed), trip.nextCandidates(),
                        trip.anchorOrderInstanceId(), trip.phase(), trip.openedAt(), trip.closedAt(),
                        trip.runtimeLineId(), trip.currentNodeId(), trip.currentLegId(), trip.planVersion(),
                        trip.positionQuality(), trip.topology());
                phase = onboard.isEmpty() && pendingPickup.isEmpty()
                        ? TripPhase.TRIP_COMPLETED_PENDING_CONFIRMATION : TripPhase.DISTRIBUTING;
            }
            evidenceTopology = topologyService.withVisitState(
                    evidenceTopology, stopId, VehicleTripTopologyService.VisitState.VISITED);
        }
        List<String> nextCandidates = !pendingPickup.isEmpty()
                ? List.copyOf(pendingPickup)
                : List.copyOf(pendingDelivery);
        VehicleTripRuntime updated = new VehicleTripRuntime(
                trip.tripId(), trip.vehicleKey(), trip.orderInstanceIds(), trip.ordersByInstanceId(),
                trip.orderMembers(), trip.queuedOrderIds(), trip.rejectedOrderIds(),
                Set.copyOf(visited), Set.copyOf(onboard), Set.copyOf(pendingPickup),
                Set.copyOf(pendingDelivery), trip.completedOrderIds(), nextCandidates,
                trip.anchorOrderInstanceId(), phase, trip.openedAt(), trip.closedAt(),
                trip.runtimeLineId(), currentNodeId, trip.currentLegId(),
                trip.planVersion(), positionQuality, evidenceTopology);
        if (planChanged) {
            VehicleTripTopologyService.TripTopology topology = topologyService.build(
                    updated.ordersByInstanceId(), updated.orderMembers(), updated.onboardOrderIds(),
                    updated.completedOrderIds(), evidenceTopology, position, null);
            updated = withTopology(updated, topology);
        }
        currentByVehicle.put(trip.vehicleKey(), updated);
        persist(updated);
        return updated;
    }

    private VehicleTripRuntime previousRuntime(String vehicleKey) {
        VehicleTripRuntime cached = currentByVehicle.get(vehicleKey);
        if (cached != null || objectMapper == null || tripsRoot == null || !restoreAttempted.add(vehicleKey)) {
            return cached;
        }
        Path path = tripFile(vehicleKey);
        if (!Files.isRegularFile(path)) return null;
        try {
            VehicleTripRuntime restored = objectMapper.readValue(path.toFile(), VehicleTripRuntime.class);
            if (restored != null && vehicleKey.equals(restored.vehicleKey())) {
                currentByVehicle.put(vehicleKey, restored);
                return restored;
            }
        } catch (Exception ignored) {
            // 损坏的运行态不得阻断订单主链；下一次 reconcile 会用事件库重建并覆盖。
        }
        return null;
    }

    private void persist(VehicleTripRuntime trip) {
        if (trip == null || objectMapper == null || tripsRoot == null) return;
        Path target = tripFile(trip.vehicleKey());
        try {
            Files.createDirectories(target.getParent());
            Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
            Files.writeString(temporary,
                    objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(trip),
                    StandardCharsets.UTF_8);
            try {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception exception) {
            log.warn("[VehicleTrip] persist failed: vehicle={}, path={}", trip.vehicleKey(), target, exception);
        }
    }

    private Path tripFile(String vehicleKey) {
        String safe = vehicleKey == null ? "UNKNOWN"
                : vehicleKey.replaceAll("[<>:\"/\\\\|?*]", "_").trim();
        return tripsRoot.resolve(safe.isBlank() ? "UNKNOWN.json" : safe + ".json");
    }

    private VehicleTripRuntime build(
            String vehicleKey,
            List<VehicleOrderChainStore.StoredOrder> members,
            VehicleTripRuntime previous
    ) {
        List<VehicleOrderChainStore.StoredOrder> ordered = members.stream()
                .sorted(Comparator.comparingLong(VehicleOrderChainStore.StoredOrder::firstObservedAtMs)
                        .thenComparing(VehicleOrderChainStore.StoredOrder::orderId)
                        .thenComparing(VehicleOrderChainStore.StoredOrder::routeKey))
                .toList();
        Map<String, VehicleOrderChainStore.StoredOrder> byInstanceId = new LinkedHashMap<>();
        Map<String, TripMemberState> memberStates = classifyMembers(ordered, previous);
        Set<String> activeInstanceIds = new LinkedHashSet<>();
        Set<String> queued = new LinkedHashSet<>();
        Set<String> rejected = new LinkedHashSet<>();
        Set<String> pendingPickup = new LinkedHashSet<>();
        Set<String> onboard = new LinkedHashSet<>();
        Set<String> completed = new LinkedHashSet<>();
        Set<String> visitedPickup = new LinkedHashSet<>();

        for (VehicleOrderChainStore.StoredOrder stored : ordered) {
            String instanceId = stored.key();
            byInstanceId.put(instanceId, stored);
            TripMemberState memberState = memberStates.getOrDefault(instanceId, TripMemberState.CANDIDATE);
            if (memberState == TripMemberState.QUEUED) {
                queued.add(instanceId);
                continue;
            }
            if (memberState == TripMemberState.REJECTED) {
                rejected.add(instanceId);
                continue;
            }
            activeInstanceIds.add(instanceId);
            String status = normalizedStatus(stored.record().status());
            String localTransit = orderStore.recordedTransitStatus(stored.record());
            boolean previouslyCompleted = previous != null && previous.completedOrderIds().contains(instanceId);
            boolean previouslyOnboard = previous != null && previous.onboardOrderIds().contains(instanceId);
            if (isCompleted(status) || previouslyCompleted) {
                completed.add(instanceId);
                visitedPickup.add(instanceId);
            } else if (isTransporting(status) || localTransit != null || previouslyOnboard) {
                onboard.add(instanceId);
                visitedPickup.add(instanceId);
            } else if (memberState == TripMemberState.CONFIRMED) {
                pendingPickup.add(instanceId);
            }
        }

        List<String> nextCandidates = !pendingPickup.isEmpty()
                ? List.copyOf(pendingPickup)
                : List.copyOf(onboard);
        TripPhase phase = phase(pendingPickup, onboard, completed);
        String anchor = selectAnchor(previous, pendingPickup, onboard, completed);
        boolean sameTrip = previous != null && intersects(previous.orderInstanceIds(), activeInstanceIds);
        long openedAt = sameTrip
                ? previous.openedAt()
                : ordered.stream()
                .filter(order -> activeInstanceIds.contains(order.key()))
                .mapToLong(VehicleOrderChainStore.StoredOrder::firstObservedAtMs).min().orElse(0L);
        String tripId = sameTrip
                ? previous.tripId()
                : "trip-" + vehicleKey + "-" + openedAt;
        Long closedAt = phase == TripPhase.TRIP_COMPLETED_PENDING_CONFIRMATION
                ? ordered.stream().mapToLong(VehicleOrderChainStore.StoredOrder::lastObservedAtMs).max().orElse(0L)
                : null;
        VehicleTripTopologyService.TripLeg previousCurrentLeg = sameTrip
                ? currentLeg(previous) : null;
        double[] preservedOrigin = previousCurrentLeg != null
                && "CURRENT_POSITION".equals(previousCurrentLeg.fromStopId())
                && previousCurrentLeg.coordinates() != null
                && !previousCurrentLeg.coordinates().isEmpty()
                ? previousCurrentLeg.coordinates().get(0) : null;
        String preservedTarget = preservedOrigin == null ? null : previousCurrentLeg.toStopId();
        VehicleTripTopologyService.TripTopology topology = topologyService.build(
                Map.copyOf(byInstanceId), Map.copyOf(memberStates), Set.copyOf(onboard),
                Set.copyOf(completed), sameTrip ? previous.topology() : null,
                preservedOrigin, preservedTarget);
        return new VehicleTripRuntime(
                tripId, vehicleKey, List.copyOf(activeInstanceIds), Map.copyOf(byInstanceId),
                Map.copyOf(memberStates), Set.copyOf(queued), Set.copyOf(rejected),
                Set.copyOf(visitedPickup), Set.copyOf(onboard), Set.copyOf(pendingPickup),
                Set.copyOf(onboard), Set.copyOf(completed), nextCandidates,
                anchor, phase, openedAt, closedAt, "trip::" + tripId,
                sameTrip ? previous.currentNodeId() : null,
                sameTrip ? previous.currentLegId() : null, topology.planVersion(),
                sameTrip ? previous.positionQuality() : PositionQuality.UNKNOWN, topology);
    }

    private Map<String, TripMemberState> classifyMembers(
            List<VehicleOrderChainStore.StoredOrder> ordered,
            VehicleTripRuntime previous
    ) {
        Map<String, TripMemberState> result = new LinkedHashMap<>();
        Map<String, TripMemberState> previousStates = previous == null ? Map.of() : previous.orderMembers();
        long openedAt = previous == null
                ? ordered.stream().mapToLong(VehicleOrderChainStore.StoredOrder::firstObservedAtMs).min().orElse(0L)
                : previous.openedAt();
        boolean hasConfirmed = false;
        for (VehicleOrderChainStore.StoredOrder stored : ordered) {
            String status = normalizedStatus(stored.record().status());
            TripMemberState old = previousStates.get(stored.key());
            if (isTransporting(status) || orderStore.recordedTransitStatus(stored.record()) != null
                    || old == TripMemberState.CONFIRMED) {
                result.put(stored.key(), TripMemberState.CONFIRMED);
                hasConfirmed = true;
            }
        }
        for (VehicleOrderChainStore.StoredOrder stored : ordered) {
            if (result.containsKey(stored.key())) continue;
            TripMemberState old = previousStates.get(stored.key());
            if (old != null) {
                result.put(stored.key(), old);
                continue;
            }
            if (isCompleted(stored.record().status())) {
                result.put(stored.key(), ordered.size() == 1 ? TripMemberState.CONFIRMED : TripMemberState.REJECTED);
                continue;
            }
            if (!hasConfirmed) {
                result.put(stored.key(), TripMemberState.CONFIRMED);
                hasConfirmed = true;
            } else if (stored.firstObservedAtMs() - openedAt <= SAME_SNAPSHOT_CANDIDATE_WINDOW_MS) {
                result.put(stored.key(), TripMemberState.CANDIDATE);
            } else {
                result.put(stored.key(), TripMemberState.QUEUED);
            }
        }
        boolean hasActiveConfirmed = ordered.stream().anyMatch(order ->
                result.get(order.key()) == TripMemberState.CONFIRMED
                        && !isCompleted(order.record().status()));
        if (!hasActiveConfirmed) {
            ordered.stream()
                    .filter(order -> !isCompleted(order.record().status()))
                    .filter(order -> result.get(order.key()) != TripMemberState.REJECTED)
                    .min(Comparator.comparingLong(VehicleOrderChainStore.StoredOrder::firstObservedAtMs)
                            .thenComparing(VehicleOrderChainStore.StoredOrder::key))
                    .ifPresent(order -> result.put(order.key(), TripMemberState.CONFIRMED));
        }
        return result;
    }

    private VehicleTripRuntime withTopology(
            VehicleTripRuntime trip,
            VehicleTripTopologyService.TripTopology topology
    ) {
        String currentNodeId = trip.currentNodeId();
        String currentLegId = trip.currentLegId();
        String existingLegId = currentLegId;
        if (existingLegId != null && topology.legs().stream()
                .noneMatch(leg -> existingLegId.equals(leg.legId()))) {
            currentLegId = null;
        }
        if (trip.phase() == TripPhase.AT_PICKUP) {
            String targetStopId = topology.plannedStopIds().stream().findFirst()
                    .orElse(trip.anchorOrderInstanceId() + "::PICKUP");
            currentNodeId = nodeForStop(topology, targetStopId);
            currentLegId = null;
        } else if ((trip.phase() == TripPhase.COLLECTING
                || trip.phase() == TripPhase.LINEHAUL
                || trip.phase() == TripPhase.DISTRIBUTING)
                && currentLegId == null && !topology.legs().isEmpty()) {
            currentNodeId = null;
            currentLegId = topology.legs().get(0).legId();
        }
        return new VehicleTripRuntime(
                trip.tripId(), trip.vehicleKey(), trip.orderInstanceIds(), trip.ordersByInstanceId(),
                trip.orderMembers(), trip.queuedOrderIds(), trip.rejectedOrderIds(),
                trip.visitedPickupOrderIds(), trip.onboardOrderIds(), trip.pendingPickupOrderIds(),
                trip.pendingDeliveryOrderIds(), trip.completedOrderIds(), trip.nextCandidates(),
                trip.anchorOrderInstanceId(), trip.phase(), trip.openedAt(), trip.closedAt(),
                trip.runtimeLineId(), currentNodeId, currentLegId, topology.planVersion(),
                trip.positionQuality(), topology);
    }

    private VehicleTripTopologyService.TripLeg currentLeg(VehicleTripRuntime trip) {
        if (trip == null || trip.topology() == null || trip.currentLegId() == null) return null;
        return trip.topology().legs().stream()
                .filter(leg -> trip.currentLegId().equals(leg.legId()))
                .findFirst().orElse(null);
    }

    private String nodeForStop(VehicleTripTopologyService.TripTopology topology, String stopId) {
        if (topology == null || stopId == null) return null;
        return topology.nodes().stream()
                .filter(node -> node.stops().stream().anyMatch(stop -> stopId.equals(stop.stopId())))
                .map(VehicleTripTopologyService.TripNode::nodeId)
                .findFirst().orElse(null);
    }

    private List<VehicleOrderChainStore.StoredOrder> latestOrderInstances(
            List<VehicleOrderChainStore.StoredOrder> candidates
    ) {
        Map<String, VehicleOrderChainStore.StoredOrder> latest = new LinkedHashMap<>();
        for (VehicleOrderChainStore.StoredOrder candidate : candidates) {
            VehicleOrderChainStore.StoredOrder current = latest.get(candidate.key());
            if (current == null || candidate.lastObservedAtMs() >= current.lastObservedAtMs()) {
                latest.put(candidate.key(), candidate);
            }
        }
        return List.copyOf(latest.values());
    }

    private Set<String> previousOrderIds(String vehicleKey) {
        VehicleTripRuntime previous = currentByVehicle.get(vehicleKey);
        return previous == null ? Set.of() : Set.copyOf(previous.orderInstanceIds());
    }

    private String selectAnchor(
            VehicleTripRuntime previous,
            Set<String> pendingPickup,
            Set<String> onboard,
            Set<String> completed
    ) {
        Set<String> preferred = !onboard.isEmpty() ? onboard : (!pendingPickup.isEmpty() ? pendingPickup : completed);
        if (previous != null && preferred.contains(previous.anchorOrderInstanceId())) {
            return previous.anchorOrderInstanceId();
        }
        return preferred.stream().findFirst().orElse(null);
    }

    private TripPhase phase(Set<String> pendingPickup, Set<String> onboard, Set<String> completed) {
        if (!onboard.isEmpty() && !pendingPickup.isEmpty()) return TripPhase.COLLECTING;
        if (!onboard.isEmpty()) return TripPhase.LINEHAUL;
        if (!pendingPickup.isEmpty()) return TripPhase.TO_FIRST_PICKUP;
        if (!completed.isEmpty()) return TripPhase.TRIP_COMPLETED_PENDING_CONFIRMATION;
        return TripPhase.TO_FIRST_PICKUP;
    }

    private boolean intersects(List<String> left, Set<String> right) {
        if (left == null || right == null) return false;
        return left.stream().anyMatch(right::contains);
    }

    private boolean isCompleted(String status) {
        String normalized = normalizedStatus(status);
        return normalized.contains("已完成") || normalized.equals("完成");
    }

    private boolean isTransporting(String status) {
        String normalized = normalizedStatus(status);
        return normalized.contains("运输中") || normalized.contains("运行中") || normalized.contains("在途");
    }

    private String normalizedStatus(String status) {
        return status == null ? "" : status.trim().toUpperCase(Locale.ROOT).replace(" ", "");
    }

    public enum TripPhase {
        TO_FIRST_PICKUP,
        AT_PICKUP,
        COLLECTING,
        LINEHAUL,
        DISTRIBUTING,
        TRIP_COMPLETED,
        TRIP_COMPLETED_PENDING_CONFIRMATION
    }

    public enum TripMemberState { CANDIDATE, CONFIRMED, QUEUED, REJECTED }
    public enum PositionQuality { FRESH, STALE, UNKNOWN }
    public enum TargetPresenceState { EN_ROUTE, ARRIVED, DWELLING, PASSING_BY, DEPARTED }

    private record StopPresenceEvidence(
            long enteredAtMs,
            long lastObservedAtMs,
            int sampleCount,
            boolean dwellConfirmed
    ) {}

    public record TargetPresenceObservation(
            TargetPresenceState state,
            VehicleTripTopologyService.TripStop target,
            double distanceKm,
            int sampleCount,
            long dwellMs
    ) {}

    public record VehicleTripRuntime(
            String tripId,
            String vehicleKey,
            List<String> orderInstanceIds,
            Map<String, VehicleOrderChainStore.StoredOrder> ordersByInstanceId,
            Map<String, TripMemberState> orderMembers,
            Set<String> queuedOrderIds,
            Set<String> rejectedOrderIds,
            Set<String> visitedPickupOrderIds,
            Set<String> onboardOrderIds,
            Set<String> pendingPickupOrderIds,
            Set<String> pendingDeliveryOrderIds,
            Set<String> completedOrderIds,
            List<String> nextCandidates,
            String anchorOrderInstanceId,
            TripPhase phase,
            long openedAt,
            Long closedAt,
            String runtimeLineId,
            String currentNodeId,
            String currentLegId,
            long planVersion,
            PositionQuality positionQuality,
            VehicleTripTopologyService.TripTopology topology
    ) {
        public VehicleOrderChainStore.StoredOrder anchorOrder() {
            return ordersByInstanceId.get(anchorOrderInstanceId);
        }

        public String openedAtIso() {
            return openedAt <= 0 ? null : Instant.ofEpochMilli(openedAt).toString();
        }
    }
}
