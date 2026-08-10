package com.jushen.digitaltwin.service;

import java.util.List;

/** 将车辆坐标投影到路线，并用历史进度窗口消除 U 型路线的近邻歧义。 */
final class RouteProgressProjector {

    private static final double EARTH_RADIUS_KM = 6_371.0088;
    private static final double SEARCH_WINDOW_RATIO = 0.3;
    private static final double WINDOW_FALLBACK_DISTANCE_SQ = 0.0001;

    private RouteProgressProjector() {
    }

    static double project(List<double[]> coordinates, double[] position, double hintProgress) {
        if (coordinates == null || coordinates.size() < 2 || position == null || position.length < 2) {
            return 0;
        }

        double totalKm = 0;
        for (int index = 1; index < coordinates.size(); index++) {
            totalKm += distanceKm(coordinates.get(index - 1), coordinates.get(index));
        }
        if (totalKm <= 0) return 0;

        boolean useWindow = hintProgress >= 0 && hintProgress <= 1;
        double windowStartKm = 0;
        double windowEndKm = totalKm;
        if (useWindow) {
            double hintKm = hintProgress * totalKm;
            windowStartKm = Math.max(0, hintKm - totalKm * SEARCH_WINDOW_RATIO);
            windowEndKm = Math.min(totalKm, hintKm + totalKm * SEARCH_WINDOW_RATIO);
        }

        double nearestDistanceSq = Double.POSITIVE_INFINITY;
        double distanceAlongKm = 0;
        double walkedKm = 0;
        for (int index = 1; index < coordinates.size(); index++) {
            double[] start = coordinates.get(index - 1);
            double[] end = coordinates.get(index);
            double segmentKm = distanceKm(start, end);
            double segmentStartKm = walkedKm;
            double segmentEndKm = walkedKm + segmentKm;

            if (useWindow && segmentEndKm < windowStartKm) {
                walkedKm = segmentEndKm;
                continue;
            }
            if (useWindow && segmentStartKm > windowEndKm) break;

            double dx = end[0] - start[0];
            double dy = end[1] - start[1];
            double segmentLengthSq = dx * dx + dy * dy;
            if (segmentLengthSq <= 0) {
                walkedKm = segmentEndKm;
                continue;
            }

            double ratio = ((position[0] - start[0]) * dx + (position[1] - start[1]) * dy)
                    / segmentLengthSq;
            ratio = Math.max(0, Math.min(1, ratio));
            double projectedLng = start[0] + dx * ratio;
            double projectedLat = start[1] + dy * ratio;
            double distanceSq = Math.pow(position[0] - projectedLng, 2)
                    + Math.pow(position[1] - projectedLat, 2);
            if (distanceSq < nearestDistanceSq) {
                nearestDistanceSq = distanceSq;
                distanceAlongKm = walkedKm + segmentKm * ratio;
            }
            walkedKm = segmentEndKm;
        }

        if (useWindow && nearestDistanceSq > WINDOW_FALLBACK_DISTANCE_SQ) {
            return project(coordinates, position, -1);
        }

        return Math.max(0, Math.min(1, distanceAlongKm / totalKm));
    }

    private static double distanceKm(double[] first, double[] second) {
        double lat1 = Math.toRadians(first[1]);
        double lat2 = Math.toRadians(second[1]);
        double deltaLat = Math.toRadians(second[1] - first[1]);
        double deltaLng = Math.toRadians(second[0] - first[0]);
        double haversine = Math.sin(deltaLat / 2) * Math.sin(deltaLat / 2)
                + Math.cos(lat1) * Math.cos(lat2)
                * Math.sin(deltaLng / 2) * Math.sin(deltaLng / 2);
        return EARTH_RADIUS_KM * 2 * Math.atan2(Math.sqrt(haversine), Math.sqrt(1 - haversine));
    }
}
