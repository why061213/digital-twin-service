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
        return build(orders, memberStates, onboardOrderIds, completedOrderIds, previous, null, null);
    }

    public TripTopology build(
            Map<String, VehicleOrderChainStore.StoredOrder> orders,
            Map<String, VehicleTripRuntimeService.TripMemberState> memberStates,
            Set<String> onboardOrderIds,
            Set<String> completedOrderIds,
            TripTopology previous,
            double[] currentPosition,
            String forcedFirstStopId
    ) {
        List<TripStop> stops = buildStops(orders, memberStates, onboardOrderIds, completedOrderIds, previous);
        List<TripNode> nodes = mergeNodes(stops, DEFAULT_NODE_MERGE_RADIUS_KM,
                previous == null ? List.of() : previous.nodes());
        double[] planningPosition = hasCoordinates(currentPosition)
                ? currentPosition : fallbackPlanningPosition(stops);
        List<String> plannedStopIds = planStops(stops, onboardOrderIds, planningPosition, forcedFirstStopId);
        List<TripLeg> legs = reusePlannedLegs(
                buildLegs(plannedStopIds, stops, onboardOrderIds, currentPosition, planningPosition), previous);
        // 计划版本只描述节点顺序；普通 GPS 位移不得制造新 planVersion/currentLegId。
        String signature = String.join(">", plannedStopIds);
        long version = previous == null ? 1L
                : signature.equals(previous.planSignature()) ? previous.planVersion() : previous.planVersion() + 1L;
        return new TripTopology(stops, nodes, plannedStopIds, legs, version, signature);
    }

    private List<TripLeg> reusePlannedLegs(List<TripLeg> legs, TripTopology previous) {
        if (previous == null || previous.legs() == null || previous.legs().isEmpty()) return legs;
        Map<String, TripLeg> previousBySegment = new LinkedHashMap<>();
        for (TripLeg leg : previous.legs()) previousBySegment.put(leg.segmentKey(), leg);
        return legs.stream().map(leg -> {
            TripLeg old = previousBySegment.get(leg.segmentKey());
            return old != null && old.coordinates() != null && old.coordinates().size() > 2
                    ? new TripLeg(leg.legId(), leg.fromStopId(), leg.toStopId(), leg.purpose(),
                    old.coordinates(), old.distanceKm(), old.durationMs(), leg.state(), leg.segmentKey())
                    : leg;
        }).toList();
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
        if (previous == null) return derived;
        return previous.ordinal() >= derived.ordinal() ? previous : derived;
    }

    public TripTopology withVisitState(TripTopology topology, String stopId, VisitState state) {
        if (topology == null || stopId == null || state == null) return topology;
        List<TripStop> stops = topology.stops().stream()
                .map(stop -> stopId.equals(stop.stopId())
                        ? new TripStop(stop.stopId(), stop.orderInstanceId(), stop.orderId(), stop.action(),
                        stop.locationName(), stop.coordinates(), stop.timeWindow(), stop.cargoDelta(),
                        monotonicState(stop.visitState(), state))
                        : stop)
                .toList();
        Map<String, TripStop> byId = new LinkedHashMap<>();
        for (TripStop stop : stops) byId.put(stop.stopId(), stop);
        List<TripNode> nodes = topology.nodes().stream()
                .map(node -> new TripNode(node.nodeId(), node.stops().stream()
                        .map(stop -> byId.getOrDefault(stop.stopId(), stop)).toList(),
                        node.coordinates(), node.internalVisitSequence()))
                .toList();
        return new TripTopology(stops, nodes, topology.plannedStopIds(), topology.legs(),
                topology.planVersion(), topology.planSignature());
    }

    /**
     * 合并订单初始化时，上游“已完成”不能直接充当车辆到过卸货点的轨迹证据。
     * 该方法只供复合展示行程重建计划前使用，显式解除指定订单卸货点的完成态；
     * 装载点仍由 onboard 集合保持为已访问，单订单与正常轨迹推进不走这里。
     */
    public TripTopology reopenDeliveriesForCompositePlanning(
            TripTopology topology,
            Set<String> orderInstanceIds
    ) {
        if (topology == null || orderInstanceIds == null || orderInstanceIds.isEmpty()) return topology;
        List<TripStop> stops = topology.stops().stream()
                .map(stop -> orderInstanceIds.contains(stop.orderInstanceId())
                        && stop.action() == StopAction.DELIVERY
                        ? new TripStop(stop.stopId(), stop.orderInstanceId(), stop.orderId(), stop.action(),
                        stop.locationName(), stop.coordinates(), stop.timeWindow(), stop.cargoDelta(),
                        VisitState.PENDING)
                        : stop)
                .toList();
        Map<String, TripStop> byId = new LinkedHashMap<>();
        for (TripStop stop : stops) byId.put(stop.stopId(), stop);
        List<TripNode> nodes = topology.nodes().stream()
                .map(node -> new TripNode(node.nodeId(), node.stops().stream()
                        .map(stop -> byId.getOrDefault(stop.stopId(), stop)).toList(),
                        node.coordinates(), node.internalVisitSequence()))
                .toList();
        return new TripTopology(stops, nodes, topology.plannedStopIds(), topology.legs(),
                topology.planVersion(), topology.planSignature());
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

    /**
     * 从车辆有效位置开始做带订单级先取后送约束的最近邻规划。
     * 已经装车的订单可以先送，再去提取另一订单；但任一订单自身绝不允许先送后取。
     */
    private List<String> planStops(
            List<TripStop> stops,
            Set<String> onboardOrderIds,
            double[] currentPosition,
            String forcedFirstStopId
    ) {
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
        double[] cursor = hasCoordinates(currentPosition) ? currentPosition : null;
        while (!remaining.isEmpty()) {
            List<TripStop> legal = remaining.values().stream()
                    .filter(stop -> stop.action() == StopAction.PICKUP
                            || pickedOrders.contains(stop.orderInstanceId()))
                    .toList();
            if (legal.isEmpty()) break;
            final double[] current = cursor;
            TripStop forced = forcedFirstStopId == null ? null : remaining.get(forcedFirstStopId);
            TripStop selected = forced != null && legal.contains(forced) ? forced : legal.stream().min(Comparator
                    .comparingDouble((TripStop stop) -> current == null ? 0d : distanceOrMax(current, stop.coordinates()))
                    .thenComparing(stop -> stop.action() == StopAction.PICKUP ? 0 : 1)
                    .thenComparingDouble(stop -> coordinate(stop.coordinates(), 0))
                    .thenComparingDouble(stop -> coordinate(stop.coordinates(), 1))
                    .thenComparing(TripStop::stopId)).orElseThrow();
            plan.add(selected.stopId());
            remaining.remove(selected.stopId());
            if (selected.action() == StopAction.PICKUP) pickedOrders.add(selected.orderInstanceId());
            if (hasCoordinates(selected.coordinates())) cursor = selected.coordinates();
        }
        return List.copyOf(plan);
    }

    private double[] fallbackPlanningPosition(List<TripStop> stops) {
        List<TripStop> pending = stops.stream()
                .filter(stop -> stop.visitState() != VisitState.VISITED)
                .filter(stop -> hasCoordinates(stop.coordinates()))
                .toList();
        List<TripStop> visited = stops.stream()
                .filter(stop -> stop.visitState() == VisitState.VISITED)
                .filter(stop -> hasCoordinates(stop.coordinates()))
                .toList();
        List<TripStop> anchors = visited.isEmpty()
                ? pending.stream().filter(stop -> stop.action() == StopAction.PICKUP).toList()
                : visited;
        return anchors.stream().min(Comparator
                        .comparingDouble((TripStop anchor) -> pending.stream()
                                .mapToDouble(stop -> haversineKm(anchor.coordinates(), stop.coordinates())).sum())
                        .thenComparingDouble(anchor -> coordinate(anchor.coordinates(), 0))
                        .thenComparingDouble(anchor -> coordinate(anchor.coordinates(), 1)))
                .map(TripStop::coordinates)
                .map(double[]::clone)
                .orElse(null);
    }

    private double coordinate(double[] coordinates, int index) {
        return hasCoordinates(coordinates) ? coordinates[index] : Double.MAX_VALUE;
    }

    private List<TripLeg> buildLegs(
            List<String> plannedStopIds,
            List<TripStop> stops,
            Set<String> onboardOrderIds,
            double[] currentPosition,
            double[] planningPosition
    ) {
        Map<String, TripStop> byId = new LinkedHashMap<>();
        for (TripStop stop : stops) byId.put(stop.stopId(), stop);
        List<String> executionSequence = new ArrayList<>();
        if (!hasCoordinates(currentPosition)) {
            stops.stream()
                    .filter(stop -> stop.action() == StopAction.PICKUP)
                    .filter(stop -> stop.visitState() == VisitState.VISITED)
                    .filter(stop -> onboardOrderIds.contains(stop.orderInstanceId()))
                    .min(Comparator
                            .comparingDouble((TripStop stop) -> hasCoordinates(planningPosition)
                                    ? distanceOrMax(planningPosition, stop.coordinates()) : 0d)
                            .thenComparingDouble(stop -> coordinate(stop.coordinates(), 0))
                            .thenComparingDouble(stop -> coordinate(stop.coordinates(), 1)))
                    .map(TripStop::stopId)
                    .ifPresent(executionSequence::add);
        }
        executionSequence.addAll(plannedStopIds);
        List<TripLeg> legs = new ArrayList<>();
        if (hasCoordinates(currentPosition) && !plannedStopIds.isEmpty()) {
            TripStop to = byId.get(plannedStopIds.get(0));
            if (to != null && hasCoordinates(to.coordinates())) {
                String segmentKey = "CURRENT_POSITION->" + to.stopId();
                legs.add(new TripLeg(
                        "leg-" + Integer.toUnsignedString(segmentKey.hashCode(), 16),
                        "CURRENT_POSITION", to.stopId(), purpose(null, to),
                        List.of(currentPosition.clone(), to.coordinates().clone()),
                        haversineKm(currentPosition, to.coordinates()), null,
                        LegState.EN_ROUTE, segmentKey));
            }
        }
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
        if (from != null && from.action() == StopAction.DELIVERY) return "DISTRIBUTING";
        return "LINEHAUL";
    }

    private String stablePositionKey(double[] coordinates) {
        if (!hasCoordinates(coordinates)) return "ORIGIN";
        return Math.round(coordinates[0] * 100_000d) + ":" + Math.round(coordinates[1] * 100_000d);
    }

    public TripStop stopById(TripTopology topology, String stopId) {
        if (topology == null || stopId == null) return null;
        return topology.stops().stream().filter(stop -> stopId.equals(stop.stopId())).findFirst().orElse(null);
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
