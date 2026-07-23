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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

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

    public EligibilityReport analyzeLatestVehicleOrders() {
        Instant now = Instant.now();
        List<VehicleOrderChainStore.StoredOrder> history = orderStore.recentStoredOrders();
        List<VehicleOrderChainStore.StoredOrder> currentSnapshot = orderStore.latestObservedStoredOrders();
        List<VehicleTripRuntimeService.VehicleTripRuntime> trips = tripRuntimeService.reconcile(
                currentSnapshot == null || currentSnapshot.isEmpty() ? history : currentSnapshot);
        if (!properties.isIgnoreOrdersWithoutRealPosition()) {
            EligibilityReport disabled = new EligibilityReport(
                    now.toString(), false, Map.of("skipped", true), Map.of("skipped", true),
                    trips.size(), 0, 0, List.of(), null);
            return disabled;
        }

        Map<String, Object> directory = routePushService.refreshProviderVehicleDirectoryNow();

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
        if (immediate.state() == WaitingOrderTrajectoryClassifier.State.LOADING) {
            return decision(trip, current, instanceId, providerVehicleId, false,
                    immediate.state().name(), immediate.reason(), position, null, immediate);
        }

        VehicleOrderChainStore.StoredOrder previous = previousCompletedOrder(current, history);
        if (previous == null) {
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
        VehicleTripRuntimeService.VehicleTripRuntime effectiveTrip =
                tripRuntimeService.applyEligibilityEvidence(trip, decision);
        return new VehicleDecision(
                current.vehicleKey(), record.orderId(), instanceId, record.status(), record.updatedAt(),
                providerVehicleId, eligible, decision, reason,
                position == null ? null : position.position(),
                previous == null ? null : previous.orderId(),
                previous == null || previous.record().to() == null ? null : previous.record().to().name(),
                previous == null || previous.record().to() == null ? null : previous.record().to().coords(),
                record.from() == null ? null : record.from().name(),
                record.from() == null ? null : record.from().coords(),
                analysis,
                effectiveTrip.tripId(), effectiveTrip.phase(), effectiveTrip.orderInstanceIds(),
                effectiveTrip.pendingPickupOrderIds(), effectiveTrip.onboardOrderIds(),
                effectiveTrip.completedOrderIds(), effectiveTrip.nextCandidates());
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
            List<String> tripOrderInstanceIds,
            Set<String> pendingPickupOrderIds,
            Set<String> onboardOrderIds,
            Set<String> completedOrderIds,
            List<String> nextCandidates
    ) {}
}
