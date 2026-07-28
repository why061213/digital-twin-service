package com.jushen.digitaltwin.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 从多订单 Trip 元数据中提取偏航重规划前后的必经业务节点。 */
final class TripRouteAnchorResolver {
    private TripRouteAnchorResolver() {
    }

    static Anchors resolve(
            Map<String, Object> metadata,
            List<double[]> baselineCoordinates,
            double currentProgress,
            ObjectMapper objectMapper
    ) {
        if (metadata == null || objectMapper == null
                || baselineCoordinates == null || baselineCoordinates.size() < 2) {
            return Anchors.empty();
        }
        Object rawStops = metadata.get("tripStops");
        if (!(rawStops instanceof List<?> values)) return Anchors.empty();
        double[] origin = baselineCoordinates.get(0);
        double[] destination = baselineCoordinates.get(baselineCoordinates.size() - 1);
        List<ProjectedStop> stops = new ArrayList<>();
        for (Object value : values) {
            Map<String, Object> stop = objectMapper.convertValue(
                    value, new TypeReference<LinkedHashMap<String, Object>>() {});
            double[] coordinates = coordinates(stop.get("coordinates"));
            if (coordinates == null) continue;
            stops.add(new ProjectedStop(
                    coordinates,
                    "VISITED".equals(String.valueOf(stop.get("visitState"))),
                    RouteProgressProjector.project(baselineCoordinates, coordinates, -1)));
        }
        stops.sort(Comparator.comparingDouble(ProjectedStop::progress));
        List<double[]> prefix = new ArrayList<>();
        List<double[]> remaining = new ArrayList<>();
        for (ProjectedStop stop : stops) {
            if (distanceKm(stop.coordinates(), origin) < 0.05
                    || distanceKm(stop.coordinates(), destination) < 0.05) continue;
            if (stop.visited() && stop.progress() <= currentProgress + 0.01) {
                addDistinct(prefix, stop.coordinates());
            } else if (!stop.visited()) {
                addDistinct(remaining, stop.coordinates());
            }
        }
        return new Anchors(List.copyOf(prefix), List.copyOf(remaining));
    }

    private static double[] coordinates(Object value) {
        if (value instanceof double[] point && point.length >= 2) return point.clone();
        if (value instanceof List<?> point && point.size() >= 2
                && point.get(0) instanceof Number lng && point.get(1) instanceof Number lat) {
            return new double[]{lng.doubleValue(), lat.doubleValue()};
        }
        return null;
    }

    private static void addDistinct(List<double[]> target, double[] coordinate) {
        if (target.stream().noneMatch(existing -> distanceKm(existing, coordinate) < 0.05)) {
            target.add(coordinate.clone());
        }
    }

    private static double distanceKm(double[] first, double[] second) {
        double lat1 = Math.toRadians(first[1]);
        double lat2 = Math.toRadians(second[1]);
        double deltaLat = Math.toRadians(second[1] - first[1]);
        double deltaLng = Math.toRadians(second[0] - first[0]);
        double haversine = Math.sin(deltaLat / 2) * Math.sin(deltaLat / 2)
                + Math.cos(lat1) * Math.cos(lat2)
                * Math.sin(deltaLng / 2) * Math.sin(deltaLng / 2);
        return 6_371.0088 * 2 * Math.atan2(Math.sqrt(haversine), Math.sqrt(1 - haversine));
    }

    private record ProjectedStop(double[] coordinates, boolean visited, double progress) {
    }

    record Anchors(List<double[]> completedWaypoints, List<double[]> remainingWaypoints) {
        static Anchors empty() {
            return new Anchors(List.of(), List.of());
        }
    }
}
