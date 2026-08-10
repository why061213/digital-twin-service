package com.jushen.digitaltwin.townroad;

import com.jushen.digitaltwin.service.PositionSnapshot;
import com.jushen.digitaltwin.service.RoutePushService;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** 按车辆当前任务簇执行严格真实定位筛选，并用稳定锚点兼容旧单路线协议。 */
@Service
public class VehicleOrderEligibilityService {
    private static final Duration MAX_PREVIOUS_ORDER_AGE = Duration.ofHours(48);

    private final RoutePushService routePushService;
    private final TownRoadMiddleLayer middleLayer;
    private final TownRoadExternalOrderProperties properties;
    private final VehicleOrderChainStore orderStore;
    private final ProviderTrajectoryClient trajectoryClient;
    private final VehicleTripRuntimeService tripRuntimeService;
    private final Map<String, Instant> lastLocalEvidenceAtByTrip = new ConcurrentHashMap<>();
    private final AtomicBoolean completionRefreshRequired = new AtomicBoolean();

    public VehicleOrderEligibilityService(
            RoutePushService routePushService,
            TownRoadMiddleLayer middleLayer,
            TownRoadExternalOrderProperties properties,
            VehicleOrderChainStore orderStore,
            ProviderTrajectoryClient trajectoryClient,
            VehicleTripRuntimeService tripRuntimeService
    ) {
        this.routePushService = routePushService;
        this.middleLayer = middleLayer;
        this.properties = properties;
        this.orderStore = orderStore;
        this.trajectoryClient = trajectoryClient;
        this.tripRuntimeService = tripRuntimeService;
    }

    /**
     * 消费 RoutePushService 本地缓存的连续位置证据。
     * 真实位置和模拟位置在这里进入同一套 ARRIVED/DWELLING/DEPARTED 状态机。
     */
    public int advanceTripsFromLocalPositionHistory() {
        int updatedCount = 0;
        for (VehicleTripRuntimeService.VehicleTripRuntime initial : tripRuntimeService.currentTrips()) {
            List<RoutePushService.RoutePositionHistorySample> history =
                    routePushService.localPositionHistoryForTrip(initial.tripId());
            if (history.isEmpty()) continue;
            Instant lastProcessed = lastLocalEvidenceAtByTrip.get(initial.tripId());
            VehicleTripRuntimeService.VehicleTripRuntime trip = initial;
            VehicleTripRuntimeService.TargetPresenceState presence =
                    VehicleTripRuntimeService.TargetPresenceState.EN_ROUTE;
            Instant newest = lastProcessed;
            double[] latestPosition = null;
            boolean consumed = false;
            for (RoutePushService.RoutePositionHistorySample sample : history) {
                if (sample == null || sample.position() == null || sample.position().length < 2) continue;
                if (lastProcessed != null && !sample.observedAt().isAfter(lastProcessed)) continue;
                consumed = true;
                latestPosition = sample.position().clone();
                if (newest == null || sample.observedAt().isAfter(newest)) newest = sample.observedAt();
                VehicleTripRuntimeService.TargetPresenceObservation observation =
                        tripRuntimeService.observeCurrentTarget(trip, sample.position(), sample.observedAt());
                presence = observation.state();
                VehicleTripTopologyService.TripStop target = observation.target();
                if (target == null) continue;
                String decision = switch (observation.state()) {
                    case ARRIVED -> "ARRIVED";
                    case DWELLING -> target.action() == VehicleTripTopologyService.StopAction.PICKUP
                            ? "LOADING" : "COMPLETED_INFERRED";
                    case DEPARTED -> "DEPARTED";
                    default -> null;
                };
                if (decision != null) {
                    if ("COMPLETED_INFERRED".equals(decision)) {
                        VehicleOrderChainStore.StoredOrder completed =
                                trip.ordersByInstanceId().get(target.orderInstanceId());
                        if (completed == null
                                || !orderStore.canInferCompletion(completed.record(), sample.observedAt())) {
                            continue;
                        }
                        if (orderStore.recordInferredCompletion(completed.record(), sample.observedAt())) {
                            completionRefreshRequired.set(true);
                        }
                    }
                    trip = tripRuntimeService.applyEligibilityEvidence(
                            trip, target.stopId(), target.orderInstanceId(), decision, sample.position());
                    if ("DEPARTED".equals(decision)) {
                        presence = VehicleTripRuntimeService.TargetPresenceState.EN_ROUTE;
                    }
                }
            }
            if (!consumed) continue;
            trip = tripRuntimeService.replanRemainingRoute(trip, latestPosition);
            lastLocalEvidenceAtByTrip.put(initial.tripId(), newest);
            VehicleTripRuntimeService.CompositeTripSnapshot snapshot =
                    tripRuntimeService.describeCompositeTrip(trip, presence);
            routePushService.setTripRuntimeMetadataForTrip(
                    trip.tripId(), runtimeMetadata(snapshot));
            updatedCount++;
        }
        return updatedCount;
    }

    public boolean consumeCompletionRefreshRequired() {
        return completionRefreshRequired.getAndSet(false);
    }

    private Map<String, Object> runtimeMetadata(
            VehicleTripRuntimeService.CompositeTripSnapshot snapshot
    ) {
        if (snapshot == null || snapshot.trip() == null) return Map.of();
        VehicleTripRuntimeService.VehicleTripRuntime trip = snapshot.trip();
        VehicleTripTopologyService.TripStop target = tripRuntimeService.currentTargetStop(trip);
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("tripId", trip.tripId());
        metadata.put("visualKey", trip.tripId());
        metadata.put("runtimeLineId", trip.runtimeLineId());
        if (trip.currentLegId() != null) metadata.put("currentLegId", trip.currentLegId());
        metadata.put("planVersion", trip.planVersion());
        metadata.put("tripPhase", trip.phase().name());
        metadata.put("tripDecision", snapshot.presence().name());
        metadata.put("positionQuality", trip.positionQuality().name());
        if (target != null) {
            metadata.put("targetStopId", target.stopId());
            metadata.put("targetOrderInstanceId", target.orderInstanceId());
            metadata.put("targetAction", target.action().name());
        }
        metadata.put("tripStatusText", snapshot.statusText());
        metadata.put("tripStops", snapshot.stops());
        metadata.put("pendingOrderCount", trip.pendingPickupOrderIds().size());
        metadata.put("onboardOrderCount", trip.onboardOrderIds().size());
        metadata.put("completedOrderCount", trip.completedOrderIds().size());
        return Map.copyOf(metadata);
    }

    public EligibilityReport analyzeLatestVehicleOrders() {
        Instant now = Instant.now();
        List<VehicleOrderChainStore.StoredOrder> history = orderStore.activeTrackingStoredOrders();
        if (history == null || history.isEmpty()) history = orderStore.recentStoredOrders();
        List<VehicleOrderChainStore.StoredOrder> currentSnapshot = orderStore.latestObservedStoredOrders();
        List<VehicleTripRuntimeService.VehicleTripRuntime> trips = tripRuntimeService.reconcile(
                currentSnapshot == null || currentSnapshot.isEmpty() ? history : currentSnapshot);

        // “不过滤无定位车辆”只控制展示准入，不能关闭已有真实位置对行程顺序的校准。
        // 两种模式都先尽力预热位置；严格模式仍会剔除无定位车辆，宽松模式则稳定降级。
        Map<String, Object> directory = routePushService.refreshProviderVehicleDirectoryNow();
        if (directory == null) directory = Map.of();
        Set<String> preparedLineIds = new LinkedHashSet<>();
        for (VehicleTripRuntimeService.VehicleTripRuntime trip : trips) {
            VehicleOrderChainStore.StoredOrder stored = trip.anchorOrder();
            if (stored == null) continue;
            ExternalOrderRecord order = stored.record();
            String instanceId = middleLayer.instanceIdFor(order);
            if (routePushService.prepareProviderPositionVehicle(
                    instanceId,
                    order.vehicle() == null ? null : order.vehicle().plate(),
                    order.vehicle() == null ? null : order.vehicle().carId())) {
                preparedLineIds.add(instanceId);
            }
        }
        Map<String, Object> positionWarmup = routePushService.warmPositionCacheForLineIds(preparedLineIds);
        if (positionWarmup == null) positionWarmup = Map.of();

        if (!properties.isIgnoreOrdersWithoutRealPosition()) {
            List<VehicleDecision> structuralDecisions = new ArrayList<>();
            for (VehicleTripRuntimeService.VehicleTripRuntime trip : trips) {
                VehicleOrderChainStore.StoredOrder current = trip.anchorOrder();
                if (current == null || current.record() == null) continue;
                String instanceId = middleLayer.instanceIdFor(current.record());
                PositionSnapshot position = routePushService.freshProviderPosition(instanceId);
                VehicleTripRuntimeService.VehicleTripRuntime compositeView =
                        tripRuntimeService.includeAllActiveOrdersForCompositeView(trip);
                if (position != null) {
                    compositeView = tripRuntimeService.evaluateDynamicInsertions(
                            compositeView, position.position());
                }
                structuralDecisions.add(decision(
                        compositeView,
                        current,
                        instanceId,
                        null,
                        false,
                        "POSITION_FILTER_DISABLED",
                        "real-position-filter-disabled",
                        position,
                        null,
                        null));
            }
            structuralDecisions.sort(Comparator.comparing(VehicleDecision::vehicleKey));
            EligibilityReport disabled = new EligibilityReport(
                    now.toString(), false, directory, positionWarmup,
                    trips.size(), 0, 0, List.copyOf(structuralDecisions), null);
            return disabled;
        }

        List<VehicleDecision> decisions = new ArrayList<>();
        int groupEligible = 0;
        int waitingAnalyzed = 0;
        for (VehicleTripRuntimeService.VehicleTripRuntime trip : trips) {
            VehicleOrderChainStore.StoredOrder current = trip.anchorOrder();
            if (current == null) continue;
            VehicleDecision decision = decide(trip, current, history, now);
            decisions.add(decision);
            if (decision.groupEligible()) groupEligible++;
            if (decision.waitingAnalysis() != null) waitingAnalyzed++;
        }
        decisions.sort(Comparator.comparing(VehicleDecision::vehicleKey));
        EligibilityReport report = new EligibilityReport(
                now.toString(), true, directory, positionWarmup,
                trips.size(), groupEligible, waitingAnalyzed,
                List.copyOf(decisions), null);
        String path = orderStore.writeEligibilityAnalysis(report);
        return new EligibilityReport(
                report.analyzedAt(), report.enabled(), report.vehicleDirectory(), report.positionWarmup(),
                report.latestVehicleCount(), report.groupEligibleCount(), report.waitingAnalyzedCount(),
                report.decisions(), path);
    }

    private VehicleDecision decide(
            VehicleTripRuntimeService.VehicleTripRuntime trip,
            VehicleOrderChainStore.StoredOrder current,
            List<VehicleOrderChainStore.StoredOrder> history,
            Instant now
    ) {
        ExternalOrderRecord order = current.record();
        String instanceId = middleLayer.instanceIdFor(order);
        String providerVehicleId = routePushService.providerVehicleIdForLineId(instanceId);
        PositionSnapshot position = routePushService.freshProviderPosition(instanceId);
        if (providerVehicleId == null || position == null) {
            return decision(trip, current, instanceId, providerVehicleId, false,
                    "NO_REAL_POSITION", "vehicle-directory-or-fresh-position-missing", position, null, null);
        }

        trip = resolveCompositeTripFromTrajectory(trip, providerVehicleId, position, now);
        trip = tripRuntimeService.evaluateDynamicInsertions(trip, position.position());
        VehicleTripTopologyService.TripStop target = tripRuntimeService.currentTargetStop(trip);
        if (target != null) {
            VehicleOrderChainStore.StoredOrder targetOrder = trip.ordersByInstanceId().get(target.orderInstanceId());
            boolean alreadyCarrying = !trip.onboardOrderIds().isEmpty();
            VehicleTripRuntimeService.TargetPresenceObservation observation =
                    tripRuntimeService.observeCurrentTarget(
                            trip, position.position(), position.providerTime());
            if (observation.state() == VehicleTripRuntimeService.TargetPresenceState.ARRIVED) {
                return decision(trip, current, instanceId, providerVehicleId, alreadyCarrying,
                        "ARRIVED", "current-target-arrived-awaiting-valid-dwell", position, null, null);
            }
            if (observation.state() == VehicleTripRuntimeService.TargetPresenceState.DWELLING) {
                boolean pickup = target.action() == VehicleTripTopologyService.StopAction.PICKUP;
                if (!pickup) {
                    Instant evidenceAt = position.providerTime();
                    if (targetOrder == null
                            || !orderStore.canInferCompletion(targetOrder.record(), evidenceAt)) {
                        return decision(trip, current, instanceId, providerVehicleId, alreadyCarrying,
                                "UNLOADING", "delivery-dwell-awaiting-valid-transit-sequence",
                                position, null, null);
                    }
                    if (orderStore.recordInferredCompletion(targetOrder.record(), evidenceAt)) {
                        completionRefreshRequired.set(true);
                    }
                }
                return decision(trip, current, instanceId, providerVehicleId, alreadyCarrying,
                        pickup ? "LOADING" : "COMPLETED_INFERRED",
                        pickup ? "pickup-valid-dwell-confirmed" : "delivery-valid-dwell-confirmed",
                        position, null, null);
            }
            if (observation.state() == VehicleTripRuntimeService.TargetPresenceState.DEPARTED) {
                if (target.action() == VehicleTripTopologyService.StopAction.PICKUP && targetOrder != null) {
                    orderStore.recordSuspectedInTransit(targetOrder.record());
                }
                return decision(trip, current, instanceId, providerVehicleId, true,
                        "DEPARTED",
                        target.action() == VehicleTripTopologyService.StopAction.PICKUP
                                ? "departed-current-target-pickup-after-valid-dwell"
                                : "departed-current-target-delivery-after-valid-dwell",
                        position, null, null);
            }
            if (alreadyCarrying && target.action() == VehicleTripTopologyService.StopAction.PICKUP) {
                return decision(trip, current, instanceId, providerVehicleId, true,
                        "EN_ROUTE_TO_PICKUP", "onboard-orders-with-confirmed-pickup-ahead",
                        position, null, null);
            }
            if (alreadyCarrying && target.action() == VehicleTripTopologyService.StopAction.DELIVERY) {
                return decision(trip, current, instanceId, providerVehicleId, true,
                        "EN_ROUTE_TO_DELIVERY", "onboard-orders-with-confirmed-delivery-ahead",
                        position, null, null);
            }
        }

        String status = normalizeStatus(order.status());
        Instant orderTime = parseBusinessTime(order.updatedAt());
        if (isCompleted(status)) {
            if (orderTime == null) {
                return decision(trip, current, instanceId, providerVehicleId, false,
                        "UNKNOWN", "completed-time-missing", position, null, null);
            }
            Duration age = Duration.between(orderTime, now);
            Duration retention = Duration.ofMinutes(Math.max(0, properties.getCompletedRetentionMinutes()));
            boolean recent = !age.isNegative() && age.compareTo(retention) <= 0;
            return decision(trip, current, instanceId, providerVehicleId, recent,
                    recent ? "COMPLETED_RECENT" : "COMPLETED_EXPIRED",
                    recent ? "completed-within-retention-window" : "completed-over-retention-window",
                    position, null, null);
        }
        if (isTransporting(status)) {
            return decision(trip, current, instanceId, providerVehicleId, true,
                    "TRANSPORTING", "trip-anchor-is-transporting", position, null, null);
        }
        String recordedTransitStatus = orderStore.recordedTransitStatus(order);
        if (recordedTransitStatus != null) {
            return decision(trip, current, instanceId, providerVehicleId, true,
                    "TRANSPORTING_RECORDED", "vehicle-order-chain-status:" + recordedTransitStatus,
                    position, null, null);
        }
        if (!isWaiting(status)) {
            return decision(trip, current, instanceId, providerVehicleId, false,
                    "UNKNOWN", "unsupported-trip-anchor-status", position, null, null);
        }
        if (!properties.isAutoClassifyWaitingOrders()) {
            return decision(trip, current, instanceId, providerVehicleId, false,
                    "UNKNOWN", "waiting-auto-classification-disabled", position, null, null);
        }

        double[] loading = order.from() == null ? null : order.from().coords();
        WaitingOrderTrajectoryClassifier.Classification immediate =
                WaitingOrderTrajectoryClassifier.classify(position.position(), loading, List.of());
        if (target == null && immediate.state() == WaitingOrderTrajectoryClassifier.State.LOADING) {
            return decision(trip, current, instanceId, providerVehicleId, false,
                    immediate.state().name(), immediate.reason(), position, null, immediate);
        }

        VehicleOrderChainStore.StoredOrder previous = previousCompletedOrder(current, history);
        if (previous == null) {
            if (target != null && target.action() == VehicleTripTopologyService.StopAction.PICKUP) {
                return decision(trip, current, instanceId, providerVehicleId, false,
                        "EN_ROUTE_TO_PICKUP", "outside-configured-arrival-radius",
                        position, null, immediate);
            }
            return decision(trip, current, instanceId, providerVehicleId, false,
                    "UNKNOWN", "previous-completed-order-missing", position, null, immediate);
        }
        Instant previousCompletedAt = parseBusinessTime(previous.record().updatedAt());
        if (previousCompletedAt == null || previousCompletedAt.isAfter(now)
                || Duration.between(previousCompletedAt, now).compareTo(MAX_PREVIOUS_ORDER_AGE) > 0) {
            return decision(trip, current, instanceId, providerVehicleId, false,
                    "UNKNOWN", "previous-completed-order-outside-48-hours", position, previous, immediate);
        }

        ProviderTrajectoryClient.TrajectoryResult trajectory =
                trajectoryClient.fetch(providerVehicleId, previousCompletedAt, now);
        if (!trajectory.success()) {
            WaitingOrderTrajectoryClassifier.Classification unknown =
                    WaitingOrderTrajectoryClassifier.Classification.unknown(
                            "trajectory-fetch-failed:" + trajectory.reason());
            return decision(trip, current, instanceId, providerVehicleId, false,
                    "UNKNOWN", unknown.reason(), position, previous, unknown);
        }
        WaitingOrderTrajectoryClassifier.Classification classification =
                WaitingOrderTrajectoryClassifier.classify(
                        position.position(), loading, trajectory.points(), previousCompletedAt, now);
        if (classification.state() == WaitingOrderTrajectoryClassifier.State.DEPARTED) {
            orderStore.recordSuspectedInTransit(order);
        }
        return decision(trip, current, instanceId, providerVehicleId, classification.groupEligible(),
                classification.state().name(), classification.reason(), position, previous, classification);
    }

    private VehicleTripRuntimeService.VehicleTripRuntime resolveCompositeTripFromTrajectory(
            VehicleTripRuntimeService.VehicleTripRuntime trip,
            String providerVehicleId,
            PositionSnapshot currentPosition,
            Instant now
    ) {
        long activeMembers = trip.orderMembers().values().stream()
                .filter(state -> state != VehicleTripRuntimeService.TripMemberState.QUEUED
                        && state != VehicleTripRuntimeService.TripMemberState.REJECTED)
                .count();
        if (activeMembers < 2 || providerVehicleId == null || providerVehicleId.isBlank()) return trip;
        Instant earliest = now.minus(Duration.ofHours(48));
        Instant openedAt = trip.openedAt() > 0 ? Instant.ofEpochMilli(trip.openedAt()) : earliest;
        Instant start = openedAt.isBefore(earliest) ? earliest : openedAt;
        ProviderTrajectoryClient.TrajectoryResult trajectory = trajectoryClient.fetch(providerVehicleId, start, now);
        if (trajectory == null || !trajectory.success() || trajectory.points() == null) return trip;
        List<VehicleTripRuntimeService.TripPosition> positions = new ArrayList<>();
        trajectory.points().forEach(point -> positions.add(new VehicleTripRuntimeService.TripPosition(
                point.time(), new double[]{point.lng(), point.lat()})));
        Instant observedAt = currentPosition.providerTime() == null ? now : currentPosition.providerTime();
        positions.add(new VehicleTripRuntimeService.TripPosition(observedAt, currentPosition.position()));
        try {
            VehicleTripRuntimeService.CompositeTripSnapshot resolved =
                    tripRuntimeService.resolveCompositeTrip(trip, positions);
            return resolved == null ? trip : resolved.trip();
        } catch (IllegalArgumentException ignored) {
            return trip;
        }
    }

    private VehicleDecision decision(
            VehicleTripRuntimeService.VehicleTripRuntime trip,
            VehicleOrderChainStore.StoredOrder current,
            String instanceId,
            String providerVehicleId,
            boolean eligible,
            String decision,
            String reason,
            PositionSnapshot position,
            VehicleOrderChainStore.StoredOrder previous,
            WaitingOrderTrajectoryClassifier.Classification analysis
    ) {
        ExternalOrderRecord record = current.record();
        VehicleTripTopologyService.TripStop target = tripRuntimeService.currentTargetStop(trip);
        VehicleTripRuntimeService.VehicleTripRuntime effectiveTrip =
                tripRuntimeService.applyEligibilityEvidence(
                        trip,
                        target == null ? null : target.stopId(),
                        target == null ? trip.anchorOrderInstanceId() : target.orderInstanceId(),
                        decision,
                        position == null ? null : position.position());
        VehicleTripTopologyService.TripLeg currentLeg = effectiveTrip.topology().legs().stream()
                .filter(leg -> leg.legId().equals(effectiveTrip.currentLegId()))
                .findFirst().orElse(null);
        VehicleTripTopologyService.TripStop effectiveTarget =
                tripRuntimeService.currentTargetStop(effectiveTrip);
        boolean locallyCompleted = effectiveTrip.phase()
                == VehicleTripRuntimeService.TripPhase.TRIP_COMPLETED_PENDING_CONFIRMATION
                && !isCompleted(normalizeStatus(record.status()))
                && effectiveTrip.pendingPickupOrderIds().isEmpty()
                && effectiveTrip.onboardOrderIds().isEmpty()
                && effectiveTrip.pendingDeliveryOrderIds().isEmpty();
        boolean effectiveEligible = locallyCompleted ? false : eligible;
        String effectiveDecision = locallyCompleted ? "TRIP_COMPLETED_LOCAL" : decision;
        String effectiveReason = locallyCompleted
                ? "all-local-deliveries-completed-awaiting-upstream-confirmation" : reason;
        double[] legOrigin = currentLeg != null && !currentLeg.coordinates().isEmpty()
                ? currentLeg.coordinates().get(0) : position == null ? null : position.position();
        double[] legDestination = effectiveTarget == null ? null : effectiveTarget.coordinates();
        VehicleTripRuntimeService.TargetPresenceState presence = switch (effectiveDecision) {
            case "ARRIVED" -> VehicleTripRuntimeService.TargetPresenceState.ARRIVED;
            case "LOADING", "UNLOADING", "COMPLETED_INFERRED" ->
                    VehicleTripRuntimeService.TargetPresenceState.DWELLING;
            default -> VehicleTripRuntimeService.TargetPresenceState.EN_ROUTE;
        };
        VehicleTripRuntimeService.CompositeTripSnapshot composite =
                tripRuntimeService.describeCompositeTrip(effectiveTrip, presence);
        return new VehicleDecision(
                current.vehicleKey(), record.orderId(), instanceId, record.status(), record.updatedAt(),
                providerVehicleId, effectiveEligible, effectiveDecision, effectiveReason,
                position == null ? null : position.position(),
                previous == null ? null : previous.orderId(),
                previous == null || previous.record().to() == null ? null : previous.record().to().name(),
                previous == null || previous.record().to() == null ? null : previous.record().to().coords(),
                record.from() == null ? null : record.from().name(),
                record.from() == null ? null : record.from().coords(),
                analysis,
                effectiveTrip.tripId(), effectiveTrip.phase(), effectiveTrip.positionQuality(),
                effectiveTrip.orderInstanceIds(),
                effectiveTrip.pendingPickupOrderIds(), effectiveTrip.onboardOrderIds(),
                effectiveTrip.completedOrderIds(), effectiveTrip.nextCandidates(),
                effectiveTrip.orderMembers(), effectiveTrip.queuedOrderIds(),
                effectiveTrip.runtimeLineId(), effectiveTrip.currentLegId(), effectiveTrip.planVersion(),
                effectiveTarget == null ? null : effectiveTarget.stopId(),
                effectiveTarget == null ? null : effectiveTarget.orderInstanceId(),
                effectiveTarget == null ? null : effectiveTarget.action().name(),
                legOrigin, legDestination,
                effectiveTarget == null ? null : effectiveTarget.locationName(),
                composite == null ? null : composite.statusText(),
                composite == null ? List.of() : composite.stops());
    }

    private VehicleOrderChainStore.StoredOrder previousCompletedOrder(
            VehicleOrderChainStore.StoredOrder current,
            List<VehicleOrderChainStore.StoredOrder> history
    ) {
        return history.stream()
                .filter(candidate -> current.vehicleKey().equals(candidate.vehicleKey()))
                .filter(candidate -> !sameOrder(current, candidate))
                .filter(candidate -> isCompleted(normalizeStatus(candidate.record().status())))
                .filter(candidate -> compareEffectiveTime(candidate, current) <= 0)
                .max(this::compareEffectiveTime)
                .orElse(null);
    }

    private boolean sameOrder(
            VehicleOrderChainStore.StoredOrder left,
            VehicleOrderChainStore.StoredOrder right
    ) {
        return normalize(left.orderId()).equals(normalize(right.orderId()));
    }

    private int compareEffectiveTime(
            VehicleOrderChainStore.StoredOrder left,
            VehicleOrderChainStore.StoredOrder right
    ) {
        Instant leftTime = parseTime(left.record().updatedAt(), left.lastObservedAtMs());
        Instant rightTime = parseTime(right.record().updatedAt(), right.lastObservedAtMs());
        return leftTime.compareTo(rightTime);
    }

    private Instant parseTime(String value, long fallbackObservedAt) {
        Instant businessTime = parseBusinessTime(value);
        if (businessTime != null) return businessTime;
        return fallbackObservedAt > 0 ? Instant.ofEpochMilli(fallbackObservedAt) : Instant.EPOCH;
    }

    private Instant parseBusinessTime(String value) {
        if (value != null && !value.isBlank()) {
            try {
                return Instant.parse(value.trim());
            } catch (Exception ignored) {
                try {
                    return LocalDateTime.parse(value.trim().replace(' ', 'T'))
                            .atZone(ZoneId.systemDefault()).toInstant();
                } catch (Exception ignoredAgain) {
                    return null;
                }
            }
        }
        return null;
    }

    private String normalizeStatus(String status) {
        return normalize(status).replace(" ", "");
    }

    private boolean isCompleted(String status) {
        return status.contains("已完成") || status.equals("完成");
    }

    private boolean isTransporting(String status) {
        return status.contains("运输中") || status.contains("运行中");
    }

    private boolean isWaiting(String status) {
        return status.contains("待装载") || status.contains("待装货");
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    public record EligibilityReport(
            String analyzedAt,
            boolean enabled,
            Map<String, Object> vehicleDirectory,
            Map<String, Object> positionWarmup,
            int latestVehicleCount,
            int groupEligibleCount,
            int waitingAnalyzedCount,
            List<VehicleDecision> decisions,
            String analysisFile
    ) {}

    public record VehicleDecision(
            String vehicleKey,
            String orderId,
            String lineId,
            String orderStatus,
            String orderUpdatedAt,
            String providerVehicleId,
            boolean groupEligible,
            String decision,
            String reason,
            double[] currentPosition,
            String previousOrderId,
            String previousDestination,
            double[] previousDestinationPosition,
            String loadingOrigin,
            double[] loadingOriginPosition,
            WaitingOrderTrajectoryClassifier.Classification waitingAnalysis,
            String tripId,
            VehicleTripRuntimeService.TripPhase tripPhase,
            VehicleTripRuntimeService.PositionQuality positionQuality,
            List<String> tripOrderInstanceIds,
            Set<String> pendingPickupOrderIds,
            Set<String> onboardOrderIds,
            Set<String> completedOrderIds,
            List<String> nextCandidates,
            Map<String, VehicleTripRuntimeService.TripMemberState> tripOrderMembers,
            Set<String> queuedOrderIds,
            String runtimeLineId,
            String currentLegId,
            long planVersion,
            String targetStopId,
            String targetOrderInstanceId,
            String targetAction,
            double[] currentLegOriginPosition,
            double[] currentLegDestinationPosition,
            String currentLegDestination,
            String tripStatusText,
            List<VehicleTripRuntimeService.CompositeStopView> tripStops
    ) {
        public VehicleDecision(
                String vehicleKey, String orderId, String lineId, String orderStatus,
                String orderUpdatedAt, String providerVehicleId, boolean groupEligible,
                String decision, String reason, double[] currentPosition,
                String previousOrderId, String previousDestination, double[] previousDestinationPosition,
                String loadingOrigin, double[] loadingOriginPosition,
                WaitingOrderTrajectoryClassifier.Classification waitingAnalysis,
                String tripId, VehicleTripRuntimeService.TripPhase tripPhase,
                VehicleTripRuntimeService.PositionQuality positionQuality,
                List<String> tripOrderInstanceIds, Set<String> pendingPickupOrderIds,
                Set<String> onboardOrderIds, Set<String> completedOrderIds,
                List<String> nextCandidates,
                Map<String, VehicleTripRuntimeService.TripMemberState> tripOrderMembers,
                Set<String> queuedOrderIds, String runtimeLineId, String currentLegId,
                long planVersion, String targetStopId, String targetOrderInstanceId,
                String targetAction, double[] currentLegOriginPosition,
                double[] currentLegDestinationPosition, String currentLegDestination
        ) {
            this(vehicleKey, orderId, lineId, orderStatus, orderUpdatedAt, providerVehicleId,
                    groupEligible, decision, reason, currentPosition, previousOrderId,
                    previousDestination, previousDestinationPosition, loadingOrigin,
                    loadingOriginPosition, waitingAnalysis, tripId, tripPhase, positionQuality,
                    tripOrderInstanceIds, pendingPickupOrderIds, onboardOrderIds,
                    completedOrderIds, nextCandidates, tripOrderMembers, queuedOrderIds,
                    runtimeLineId, currentLegId, planVersion, targetStopId, targetOrderInstanceId,
                    targetAction, currentLegOriginPosition, currentLegDestinationPosition,
                    currentLegDestination, null, List.of());
        }

        public VehicleDecision(
                String vehicleKey, String orderId, String lineId, String orderStatus,
                String orderUpdatedAt, String providerVehicleId, boolean groupEligible,
                String decision, String reason, double[] currentPosition,
                String previousOrderId, String previousDestination, double[] previousDestinationPosition,
                String loadingOrigin, double[] loadingOriginPosition,
                WaitingOrderTrajectoryClassifier.Classification waitingAnalysis,
                String tripId, VehicleTripRuntimeService.TripPhase tripPhase,
                List<String> tripOrderInstanceIds, Set<String> pendingPickupOrderIds,
                Set<String> onboardOrderIds, Set<String> completedOrderIds,
                List<String> nextCandidates,
                Map<String, VehicleTripRuntimeService.TripMemberState> tripOrderMembers,
                Set<String> queuedOrderIds
        ) {
            this(vehicleKey, orderId, lineId, orderStatus, orderUpdatedAt, providerVehicleId,
                    groupEligible, decision, reason, currentPosition, previousOrderId,
                    previousDestination, previousDestinationPosition, loadingOrigin,
                    loadingOriginPosition, waitingAnalysis, tripId, tripPhase,
                    VehicleTripRuntimeService.PositionQuality.UNKNOWN,
                    tripOrderInstanceIds, pendingPickupOrderIds, onboardOrderIds,
                    completedOrderIds, nextCandidates, tripOrderMembers, queuedOrderIds,
                    null, null, 0L, null, null, null, null, null, null, null, List.of());
        }
    }
}
