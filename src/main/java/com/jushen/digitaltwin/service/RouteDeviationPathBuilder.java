package com.jushen.digitaltwin.service;

import java.util.ArrayList;
import java.util.List;

/** Extracts the single visible section where a vehicle-specific route differs from the baseline. */
final class RouteDeviationPathBuilder {
    private static final double DIVERGENCE_THRESHOLD_KM = 0.30;

    private RouteDeviationPathBuilder() {
    }

    static List<double[]> extract(List<double[]> baseline, List<double[]> vehicleRoute) {
        if (baseline == null || baseline.size() < 2 || vehicleRoute == null || vehicleRoute.size() < 2) {
            return List.of();
        }

        int firstDifferent = -1;
        int lastDifferent = -1;
        for (int i = 0; i < vehicleRoute.size(); i++) {
            double[] point = vehicleRoute.get(i);
            if (!valid(point)) continue;
            if (distanceToPathKm(baseline, point) > DIVERGENCE_THRESHOLD_KM) {
                if (firstDifferent < 0) firstDifferent = i;
                lastDifferent = i;
            }
        }
        if (firstDifferent < 0) return List.of();

        int from = Math.max(0, firstDifferent - 1);
        int to = Math.min(vehicleRoute.size() - 1, lastDifferent + 1);
        List<double[]> branch = new ArrayList<>(to - from + 1);
        for (int i = from; i <= to; i++) {
            double[] point = vehicleRoute.get(i);
            if (valid(point)) branch.add(new double[]{point[0], point[1]});
        }
        return branch.size() >= 2 ? List.copyOf(branch) : List.of();
    }

    private static double distanceToPathKm(List<double[]> path, double[] point) {
        double nearestKm = Double.POSITIVE_INFINITY;
        for (int i = 1; i < path.size(); i++) {
            double[] start = path.get(i - 1);
            double[] end = path.get(i);
            if (!valid(start) || !valid(end)) continue;
            double dx = end[0] - start[0];
            double dy = end[1] - start[1];
            double lengthSq = dx * dx + dy * dy;
            double ratio = lengthSq <= 0 ? 0
                    : ((point[0] - start[0]) * dx + (point[1] - start[1]) * dy) / lengthSq;
            ratio = Math.max(0, Math.min(1, ratio));
            double[] projected = {start[0] + dx * ratio, start[1] + dy * ratio};
            nearestKm = Math.min(nearestKm, distanceKm(projected, point));
        }
        return nearestKm;
    }

    private static double distanceKm(double[] start, double[] end) {
        double earthRadiusKm = 6_371.0;
        double startLat = Math.toRadians(start[1]);
        double endLat = Math.toRadians(end[1]);
        double deltaLat = Math.toRadians(end[1] - start[1]);
        double deltaLng = Math.toRadians(end[0] - start[0]);
        double a = Math.sin(deltaLat / 2) * Math.sin(deltaLat / 2)
                + Math.cos(startLat) * Math.cos(endLat)
                * Math.sin(deltaLng / 2) * Math.sin(deltaLng / 2);
        return earthRadiusKm * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    private static boolean valid(double[] coordinate) {
        return coordinate != null && coordinate.length >= 2
                && Double.isFinite(coordinate[0]) && Double.isFinite(coordinate[1]);
    }
}
