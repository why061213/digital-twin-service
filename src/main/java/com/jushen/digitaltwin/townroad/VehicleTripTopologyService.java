package com.jushen.digitaltwin.townroad;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 从任务成员生成装卸 Stop、融合 Node，并用本地距离生成满足先取后送约束的初始计划。 */
@Service
public class VehicleTripTopologyService {
    static final double DEFAULT_NODE_MERGE_RADIUS_KM = 5d;

    public TripTopology build(
            Map<String, VehicleOrderChainStore.StoredOrder> orders,
            Map<String, VehicleTripRuntimeService.TripMemberState> memberStates,
            Set<String> onboardOrderIds,
            Set<String> completedOrderIds,
            TripTopology previous
    ) {
        List<TripStop> stops = buildStops(orders, memberStates, onboardOrderIds, completedOrderIds, previous);
        List<TripNode> nodes = mergeNodes(stops, DEFAULT_NODE_MERGE_RADIUS_KM,
                previous == null ? List.of() : previous.nodes());
        List<String> plannedStopIds = planStops(stops, onboardOrderIds);
        List<TripLeg> legs = buildLegs(plannedStopIds, stops, onboardOrderIds);
        String signature = String.join(">", plannedStopIds);
        long version = previous == null ? 1L
                : signature.equals(previous.planSignature()) ? previous.planVersion() : previous.planVersion() + 1L;
        return new TripTopology(stops, nodes, plannedStopIds, legs, version, signature);
    }

    private List<TripStop> buildStops(
            Map<String, VehicleOrderChainStore.StoredOrder> orders,
            Map<String, VehicleTripRuntimeService.TripMemberState> memberStates,
            Set<String> onboardOrderIds,
            Set<String> completedOrderIds,
            TripTopology previous
    ) {
        Map<String, VisitState> previousStates = new LinkedHashMap<>();
        if (previous != null) {
            for (TripStop stop : previous.stops()) previousStates.put(stop.stopId(), stop.visitState());
        }
        List<TripStop> result = new ArrayList<>();
        for (Map.Entry<String, VehicleOrderChainStore.StoredOrder> entry : orders.entrySet()) {
            String instanceId = entry.getKey();
            VehicleTripRuntimeService.TripMemberState memberState = memberStates.get(instanceId);
            if (memberState != VehicleTripRuntimeService.TripMemberState.CONFIRMED) {
                // 候选订单只进入任务成员诊断；未被证据确认前不得改变当前执行计划。
                continue;
            }
            ExternalOrderRecord record = entry.getValue().record();
            VisitState pickupState = completedOrderIds.contains(instanceId) || onboardOrderIds.contains(instanceId)
                    ? VisitState.VISITED : VisitState.PENDING;
            VisitState deliveryState = completedOrderIds.contains(instanceId)
                    ? VisitState.VISITED : VisitState.PENDING;
            result.add(stop(instanceId, record, StopAction.PICKUP, record.from(),
                    monotonicState(previousStates.get(instanceId + "::PICKUP"), pickupState)));
            result.add(stop(instanceId, record, StopAction.DELIVERY, record.to(),
                    monotonicState(previousStates.get(instanceId + "::DELIVERY"), deliveryState)));
        }
        result.sort(Comparator.comparing(TripStop::stopId));
        return List.copyOf(result);
    }

    private TripStop stop(
            String instanceId,
            ExternalOrderRecord record,
            StopAction action,
            ExternalOrderRecord.Location location,
            VisitState state
    ) {
        double[] coordinates = location == null || location.coords() == null
                ? null : location.coords().clone();
        return new TripStop(
                instanceId + "::" + action.name(), instanceId, record.orderId(), action,
                location == null ? null : location.name(), coordinates, null,
                cargoDelta(record, action), state);
    }

    private Double cargoDelta(ExternalOrderRecord record, StopAction action) {
        Double weight = record.vehicle() == null ? null : record.vehicle().cargoWeight();
        if (weight == null) return null;
        return action == StopAction.PICKUP ? weight : -weight;
    }

    private VisitState monotonicState(VisitState previous, VisitState derived) {
        if (previous == VisitState.VISITED) return VisitState.VISITED;
        return derived;
    }

    private List<TripNode> mergeNodes(List<TripStop> stops, double radiusKm, List<TripNode> previousNodes) {
        List<List<TripStop>> clusters = new ArrayList<>();
        for (TripStop stop : stops) {
            if (!hasCoordinates(stop.coordinates())) {
                clusters.add(new ArrayList<>(List.of(stop)));
                continue;
            }
            List<TripStop> target = null;
            for (List<TripStop> cluster : clusters) {
                double[] center = center(cluster);
                if (hasCoordinates(center) && haversineKm(center, stop.coordinates()) <= radiusKm) {
                    target = cluster;
                    break;
                }
            }
            if (target == null) clusters.add(new ArrayList<>(List.of(stop)));
            else target.add(stop);
        }
        List<TripNode> nodes = new ArrayList<>();
        for (List<TripStop> cluster : clusters) {
            List<TripStop> internal = cluster.stream()
                    .sorted(Comparator.comparing(TripStop::action).thenComparing(TripStop::stopId))
                    .toList();
            String stableKey = internal.stream().map(TripStop::stopId).sorted().reduce((a, b) -> a + "|" + b).orElse("empty");
            Set<String> internalStopIds = internal.stream().map(TripStop::stopId)
                    .collect(java.util.stream.Collectors.toSet());
            String previousNodeId = previousNodes.stream()
                    .filter(node -> node.stops().stream()
                            .map(TripStop::stopId).anyMatch(internalStopIds::contains))
                    .map(TripNode::nodeId)
                    .findFirst().orElse(null);
            nodes.add(new TripNode(
                    previousNodeId == null
                            ? "node-" + Integer.toUnsignedString(stableKey.hashCode(), 16)
                            : previousNodeId,
                    internal, center(cluster), internal.stream().map(TripStop::stopId).toList()));
        }
        nodes.sort(Comparator.comparing(TripNode::nodeId));
        return List.copyOf(nodes);
    }

    /** 本地贪心拓扑排序：Delivery 只有在同订单 Pickup 已访问/已排入计划后才合法。 */
    private List<String> planStops(List<TripStop> stops, Set<String> onboardOrderIds) {
        Map<String, TripStop> remaining = new LinkedHashMap<>();
        Set<String> pickedOrders = new LinkedHashSet<>(onboardOrderIds);
        for (TripStop stop : stops) {
            if (stop.visitState() == VisitState.VISITED) {
                if (stop.action() == StopAction.PICKUP) pickedOrders.add(stop.orderInstanceId());
            } else {
                remaining.put(stop.stopId(), stop);
            }
        }
        List<String> plan = new ArrayList<>();
        double[] cursor = null;
        while (!remaining.isEmpty()) {
            List<TripStop> legal = remaining.values().stream()
                    .filter(stop -> stop.action() == StopAction.PICKUP
                            || pickedOrders.contains(stop.orderInstanceId()))
                    .toList();
            if (legal.isEmpty()) break;
            final double[] current = cursor;
            TripStop selected = legal.stream().min(Comparator
                    .comparingDouble((TripStop stop) -> current == null ? 0d : distanceOrMax(current, stop.coordinates()))
                    .thenComparing(stop -> stop.action() == StopAction.PICKUP ? 0 : 1)
                    .thenComparing(TripStop::stopId)).orElseThrow();
            plan.add(selected.stopId());
            remaining.remove(selected.stopId());
            if (selected.action() == StopAction.PICKUP) pickedOrders.add(selected.orderInstanceId());
            if (hasCoordinates(selected.coordinates())) cursor = selected.coordinates();
        }
        return List.copyOf(plan);
    }

    private List<TripLeg> buildLegs(
            List<String> plannedStopIds,
            List<TripStop> stops,
            Set<String> onboardOrderIds
    ) {
        Map<String, TripStop> byId = new LinkedHashMap<>();
        for (TripStop stop : stops) byId.put(stop.stopId(), stop);
        List<String> executionSequence = new ArrayList<>();
        stops.stream()
                .filter(stop -> stop.action() == StopAction.PICKUP)
                .filter(stop -> stop.visitState() == VisitState.VISITED)
                .filter(stop -> onboardOrderIds.contains(stop.orderInstanceId()))
                .map(TripStop::stopId)
                .sorted()
                .reduce((first, second) -> second)
                .ifPresent(executionSequence::add);
        executionSequence.addAll(plannedStopIds);
        List<TripLeg> legs = new ArrayList<>();
        for (int index = 1; index < executionSequence.size(); index++) {
            TripStop from = byId.get(executionSequence.get(index - 1));
            TripStop to = byId.get(executionSequence.get(index));
            if (from == null || to == null) continue;
            double distance = hasCoordinates(from.coordinates()) && hasCoordinates(to.coordinates())
                    ? haversineKm(from.coordinates(), to.coordinates()) : -1d;
            String segmentKey = stableLocationKey(from) + "->" + stableLocationKey(to);
            legs.add(new TripLeg(
                    "leg-" + Integer.toUnsignedString(segmentKey.hashCode(), 16),
                    from.stopId(), to.stopId(), purpose(from, to), List.of(), distance,
                    null, LegState.PLANNED, segmentKey));
        }
        return List.copyOf(legs);
    }

    private String purpose(TripStop from, TripStop to) {
        if (to.action() == StopAction.PICKUP) return "COLLECTING";
        if (from.action() == StopAction.DELIVERY) return "DISTRIBUTING";
        return "LINEHAUL";
    }

    private String stableLocationKey(TripStop stop) {
        if (hasCoordinates(stop.coordinates())) {
            return Math.round(stop.coordinates()[0] * 100_000d) + ":" + Math.round(stop.coordinates()[1] * 100_000d);
        }
        return stop.stopId();
    }

    private double[] center(List<TripStop> stops) {
        double lng = 0d;
        double lat = 0d;
        int count = 0;
        for (TripStop stop : stops) {
            if (!hasCoordinates(stop.coordinates())) continue;
            lng += stop.coordinates()[0];
            lat += stop.coordinates()[1];
            count++;
        }
        return count == 0 ? null : new double[]{lng / count, lat / count};
    }

    private double distanceOrMax(double[] from, double[] to) {
        return hasCoordinates(to) ? haversineKm(from, to) : Double.MAX_VALUE;
    }

    private boolean hasCoordinates(double[] coordinates) {
        return coordinates != null && coordinates.length >= 2
                && Double.isFinite(coordinates[0]) && Double.isFinite(coordinates[1]);
    }

    static double haversineKm(double[] from, double[] to) {
        double earthRadiusKm = 6371.0088d;
        double lat1 = Math.toRadians(from[1]);
        double lat2 = Math.toRadians(to[1]);
        double deltaLat = lat2 - lat1;
        double deltaLng = Math.toRadians(to[0] - from[0]);
        double a = Math.sin(deltaLat / 2d) * Math.sin(deltaLat / 2d)
                + Math.cos(lat1) * Math.cos(lat2)
                * Math.sin(deltaLng / 2d) * Math.sin(deltaLng / 2d);
        return earthRadiusKm * 2d * Math.atan2(Math.sqrt(a), Math.sqrt(1d - a));
    }

    public enum StopAction { PICKUP, DELIVERY }
    public enum VisitState { PENDING, ARRIVED, DWELLING, VISITED }
    public enum LegState { PLANNED, AT_NODE, DEPARTED, EN_ROUTE, APPROACHING, ARRIVED, DWELLING, LEG_COMPLETED }

    public record TripStop(
            String stopId,
            String orderInstanceId,
            String orderId,
            StopAction action,
            String locationName,
            double[] coordinates,
            String timeWindow,
            Double cargoDelta,
            VisitState visitState
    ) {}

    public record TripNode(
            String nodeId,
            List<TripStop> stops,
            double[] coordinates,
            List<String> internalVisitSequence
    ) {}

    public record TripLeg(
            String legId,
            String fromStopId,
            String toStopId,
            String purpose,
            List<double[]> coordinates,
            double distanceKm,
            Long durationMs,
            LegState state,
            String segmentKey
    ) {}

    public record TripTopology(
            List<TripStop> stops,
            List<TripNode> nodes,
            List<String> plannedStopIds,
            List<TripLeg> legs,
            long planVersion,
            String planSignature
    ) {
        public static TripTopology empty() {
            return new TripTopology(List.of(), List.of(), List.of(), List.of(), 0L, "");
        }
    }
}
