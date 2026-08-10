package com.jushen.digitaltwin.service;

import java.util.ArrayList;
import java.util.List;

final class RouteCorrectionPathBuilder {
    private static final double SAME_COORD_EPSILON = 0.000001;

    private RouteCorrectionPathBuilder() {
    }

    static Result splice(
            List<double[]> currentRoute,
            double progress,
            double[] confirmedPosition,
            List<double[]> replannedRemaining
    ) {
        if (currentRoute == null || currentRoute.size() < 2) {
            throw new IllegalArgumentException("currentRoute must contain at least two coordinates");
        }
        if (!valid(confirmedPosition)) {
            throw new IllegalArgumentException("confirmedPosition is invalid");
        }

        double[] orderStart = copy(currentRoute.get(0));
        double[] orderEnd = copy(currentRoute.get(currentRoute.size() - 1));
        List<double[]> result = prefixThroughProgress(currentRoute, progress);
        addDistinct(result, confirmedPosition);

        if (replannedRemaining != null) {
            for (double[] coordinate : replannedRemaining) {
                if (valid(coordinate)) addDistinct(result, coordinate);
            }
        }

        if (result.isEmpty()) result.add(orderStart);
        result.set(0, orderStart);
        addDistinct(result, orderEnd);
        if (!same(result.get(result.size() - 1), orderEnd)) {
            result.add(orderEnd);
        } else {
            result.set(result.size() - 1, orderEnd);
        }

        double completedDistanceKm = pathLengthKm(result.subList(0, indexOfConfirmed(result, confirmedPosition) + 1));
        double totalDistanceKm = pathLengthKm(result);
        double correctedProgress = totalDistanceKm <= 0
                ? 0
                : Math.max(0, Math.min(1, completedDistanceKm / totalDistanceKm));
        return new Result(List.copyOf(result), completedDistanceKm, totalDistanceKm, correctedProgress);
    }

    /**
     * 用两段真实道路规划结果拼出“订单起点 -> 车辆当前位置 -> 订单终点”。
     * 首次真实定位偏离基准路线时，不能把车辆位置直接连到旧路线前缀，否则地图会出现穿越道路的直线。
     */
    static Result joinPlanned(
            List<double[]> currentRoute,
            double[] confirmedPosition,
            List<double[]> plannedPrefix,
            List<double[]> plannedRemaining
    ) {
        if (currentRoute == null || currentRoute.size() < 2) {
            throw new IllegalArgumentException("currentRoute must contain at least two coordinates");
        }
        if (!valid(confirmedPosition)) {
            throw new IllegalArgumentException("confirmedPosition is invalid");
        }
        if (plannedPrefix == null || plannedPrefix.size() < 2
                || plannedRemaining == null || plannedRemaining.size() < 2) {
            throw new IllegalArgumentException("both planned route segments must contain at least two coordinates");
        }

        double[] orderStart = copy(currentRoute.get(0));
        double[] orderEnd = copy(currentRoute.get(currentRoute.size() - 1));
        List<double[]> result = new ArrayList<>();
        for (double[] coordinate : plannedPrefix) {
            if (valid(coordinate)) addDistinct(result, coordinate);
        }
        addDistinct(result, confirmedPosition);
        int confirmedIndex = result.size() - 1;
        for (double[] coordinate : plannedRemaining) {
            if (valid(coordinate)) addDistinct(result, coordinate);
        }

        if (result.size() < 2) {
            throw new IllegalArgumentException("planned route segments contain no usable path");
        }
        result.set(0, orderStart);
        if (!same(result.get(result.size() - 1), orderEnd)) result.add(orderEnd);
        else result.set(result.size() - 1, orderEnd);

        double completedDistanceKm = pathLengthKm(result.subList(0, confirmedIndex + 1));
        double totalDistanceKm = pathLengthKm(result);
        double correctedProgress = totalDistanceKm <= 0
                ? 0 : Math.max(0, Math.min(1, completedDistanceKm / totalDistanceKm));
        return new Result(List.copyOf(result), completedDistanceKm, totalDistanceKm, correctedProgress);
    }

    static Partition partition(List<double[]> route, double progress) {
        if (route == null || route.size() < 2) {
            throw new IllegalArgumentException("route must contain at least two coordinates");
        }
        List<double[]> traversed = prefixThroughProgress(route, progress);
        double[] boundary = copy(traversed.get(traversed.size() - 1));
        List<double[]> remaining = suffixFromProgress(route, progress);
        if (remaining.isEmpty() || !same(boundary, remaining.get(0))) {
            remaining.add(0, boundary);
        } else {
            remaining.set(0, boundary);
        }
        return new Partition(List.copyOf(traversed), List.copyOf(remaining));
    }

    private static List<double[]> prefixThroughProgress(List<double[]> coordinates, double progress) {
        List<double[]> prefix = new ArrayList<>();
        addDistinct(prefix, coordinates.get(0));
        double totalKm = pathLengthKm(coordinates);
        double targetKm = Math.max(0, Math.min(1, progress)) * totalKm;
        double walkedKm = 0;

        for (int i = 1; i < coordinates.size(); i++) {
            double[] start = coordinates.get(i - 1);
            double[] end = coordinates.get(i);
            double segmentKm = distanceKm(start, end);
            if (walkedKm + segmentKm < targetKm && segmentKm > 0) {
                addDistinct(prefix, end);
                walkedKm += segmentKm;
                continue;
            }
            double ratio = segmentKm <= 0 ? 0 : (targetKm - walkedKm) / segmentKm;
            ratio = Math.max(0, Math.min(1, ratio));
            addDistinct(prefix, new double[]{
                    start[0] + (end[0] - start[0]) * ratio,
                    start[1] + (end[1] - start[1]) * ratio
            });
            break;
        }
        return prefix;
    }

    private static List<double[]> suffixFromProgress(List<double[]> coordinates, double progress) {
        List<double[]> suffix = new ArrayList<>();
        double totalKm = pathLengthKm(coordinates);
        double targetKm = Math.max(0, Math.min(1, progress)) * totalKm;
        double walkedKm = 0;
        for (int i = 1; i < coordinates.size(); i++) {
            double[] start = coordinates.get(i - 1);
            double[] end = coordinates.get(i);
            double segmentKm = distanceKm(start, end);
            if (walkedKm + segmentKm < targetKm && segmentKm > 0) {
                walkedKm += segmentKm;
                continue;
            }
            double ratio = segmentKm <= 0 ? 0 : (targetKm - walkedKm) / segmentKm;
            ratio = Math.max(0, Math.min(1, ratio));
            addDistinct(suffix, new double[]{
                    start[0] + (end[0] - start[0]) * ratio,
                    start[1] + (end[1] - start[1]) * ratio
            });
            for (int tail = i; tail < coordinates.size(); tail++) addDistinct(suffix, coordinates.get(tail));
            break;
        }
        if (suffix.isEmpty()) addDistinct(suffix, coordinates.get(coordinates.size() - 1));
        return suffix;
    }

    private static int indexOfConfirmed(List<double[]> coordinates, double[] confirmedPosition) {
        for (int i = coordinates.size() - 1; i >= 0; i--) {
            if (same(coordinates.get(i), confirmedPosition)) return i;
        }
        return Math.max(0, coordinates.size() - 2);
    }

    private static double pathLengthKm(List<double[]> coordinates) {
        double total = 0;
        for (int i = 1; i < coordinates.size(); i++) {
            total += distanceKm(coordinates.get(i - 1), coordinates.get(i));
        }
        return total;
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

    private static void addDistinct(List<double[]> coordinates, double[] coordinate) {
        if (!valid(coordinate)) return;
        double[] next = copy(coordinate);
        if (coordinates.isEmpty() || !same(coordinates.get(coordinates.size() - 1), next)) {
            coordinates.add(next);
        }
    }

    private static boolean valid(double[] coordinate) {
        return coordinate != null && coordinate.length >= 2
                && Double.isFinite(coordinate[0]) && Double.isFinite(coordinate[1]);
    }

    private static boolean same(double[] left, double[] right) {
        return Math.abs(left[0] - right[0]) < SAME_COORD_EPSILON
                && Math.abs(left[1] - right[1]) < SAME_COORD_EPSILON;
    }

    private static double[] copy(double[] coordinate) {
        return new double[]{coordinate[0], coordinate[1]};
    }

    record Result(
            List<double[]> coordinates,
            double completedDistanceKm,
            double totalDistanceKm,
            double progress
    ) {
    }

    record Partition(List<double[]> traversedCoordinates, List<double[]> remainingCoordinates) {}
}
