package com.jushen.digitaltwin.townroad;

import org.springframework.stereotype.Service;

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
    private final VehicleOrderChainStore orderStore;
    private final Map<String, VehicleTripRuntime> currentByVehicle = new LinkedHashMap<>();

    public VehicleTripRuntimeService(VehicleOrderChainStore orderStore) {
        this.orderStore = orderStore;
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

            VehicleTripRuntime previous = currentByVehicle.get(entry.getKey());
            VehicleTripRuntime runtime = build(entry.getKey(), members, previous);
            next.put(entry.getKey(), runtime);
        }
        currentByVehicle.clear();
        currentByVehicle.putAll(next);
        return List.copyOf(currentByVehicle.values());
    }

    public synchronized List<VehicleTripRuntime> currentTrips() {
        return List.copyOf(currentByVehicle.values());
    }

    /** 将实时位置/轨迹证据回写到运行态；不改写上游订单，也不伪造固定节点顺序。 */
    public synchronized VehicleTripRuntime applyEligibilityEvidence(
            VehicleTripRuntime trip,
            String decision
    ) {
        if (trip == null || decision == null) return trip;
        TripPhase phase = trip.phase();
        Set<String> visited = new LinkedHashSet<>(trip.visitedPickupOrderIds());
        Set<String> onboard = new LinkedHashSet<>(trip.onboardOrderIds());
        Set<String> pendingPickup = new LinkedHashSet<>(trip.pendingPickupOrderIds());
        Set<String> pendingDelivery = new LinkedHashSet<>(trip.pendingDeliveryOrderIds());
        if ("LOADING".equals(decision)) {
            phase = TripPhase.AT_PICKUP;
        } else if ("DEPARTED".equals(decision)) {
            String anchor = trip.anchorOrderInstanceId();
            pendingPickup.remove(anchor);
            visited.add(anchor);
            onboard.add(anchor);
            pendingDelivery.add(anchor);
            phase = pendingPickup.isEmpty() ? TripPhase.LINEHAUL : TripPhase.COLLECTING;
        }
        List<String> nextCandidates = !pendingPickup.isEmpty()
                ? List.copyOf(pendingPickup)
                : List.copyOf(pendingDelivery);
        VehicleTripRuntime updated = new VehicleTripRuntime(
                trip.tripId(), trip.vehicleKey(), trip.orderInstanceIds(), trip.ordersByInstanceId(),
                Set.copyOf(visited), Set.copyOf(onboard), Set.copyOf(pendingPickup),
                Set.copyOf(pendingDelivery), trip.completedOrderIds(), nextCandidates,
                trip.anchorOrderInstanceId(), phase, trip.openedAt(), trip.closedAt());
        currentByVehicle.put(trip.vehicleKey(), updated);
        return updated;
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
        Set<String> pendingPickup = new LinkedHashSet<>();
        Set<String> onboard = new LinkedHashSet<>();
        Set<String> completed = new LinkedHashSet<>();
        Set<String> visitedPickup = new LinkedHashSet<>();

        for (VehicleOrderChainStore.StoredOrder stored : ordered) {
            String instanceId = stored.key();
            byInstanceId.put(instanceId, stored);
            String status = normalizedStatus(stored.record().status());
            String localTransit = orderStore.recordedTransitStatus(stored.record());
            if (isCompleted(status)) {
                completed.add(instanceId);
                visitedPickup.add(instanceId);
            } else if (isTransporting(status) || localTransit != null) {
                onboard.add(instanceId);
                visitedPickup.add(instanceId);
            } else {
                pendingPickup.add(instanceId);
            }
        }

        List<String> nextCandidates = !pendingPickup.isEmpty()
                ? List.copyOf(pendingPickup)
                : List.copyOf(onboard);
        TripPhase phase = phase(pendingPickup, onboard, completed);
        String anchor = selectAnchor(previous, pendingPickup, onboard, completed);
        long openedAt = previous != null && intersects(previous.orderInstanceIds(), byInstanceId.keySet())
                ? previous.openedAt()
                : ordered.stream().mapToLong(VehicleOrderChainStore.StoredOrder::firstObservedAtMs).min().orElse(0L);
        String tripId = previous != null && previous.openedAt() == openedAt
                ? previous.tripId()
                : "trip-" + vehicleKey + "-" + openedAt;
        Long closedAt = phase == TripPhase.TRIP_COMPLETED_PENDING_CONFIRMATION
                ? ordered.stream().mapToLong(VehicleOrderChainStore.StoredOrder::lastObservedAtMs).max().orElse(0L)
                : null;
        return new VehicleTripRuntime(
                tripId, vehicleKey, List.copyOf(byInstanceId.keySet()), Map.copyOf(byInstanceId),
                Set.copyOf(visitedPickup), Set.copyOf(onboard), Set.copyOf(pendingPickup),
                Set.copyOf(onboard), Set.copyOf(completed), nextCandidates,
                anchor, phase, openedAt, closedAt);
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

    public record VehicleTripRuntime(
            String tripId,
            String vehicleKey,
            List<String> orderInstanceIds,
            Map<String, VehicleOrderChainStore.StoredOrder> ordersByInstanceId,
            Set<String> visitedPickupOrderIds,
            Set<String> onboardOrderIds,
            Set<String> pendingPickupOrderIds,
            Set<String> pendingDeliveryOrderIds,
            Set<String> completedOrderIds,
            List<String> nextCandidates,
            String anchorOrderInstanceId,
            TripPhase phase,
            long openedAt,
            Long closedAt
    ) {
        public VehicleOrderChainStore.StoredOrder anchorOrder() {
            return ordersByInstanceId.get(anchorOrderInstanceId);
        }

        public String openedAtIso() {
            return openedAt <= 0 ? null : Instant.ofEpochMilli(openedAt).toString();
        }
    }
}
