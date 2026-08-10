package com.jushen.digitaltwin.townroad;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** 待装载车辆的纯轨迹判定器，不执行网络请求。 */
public final class WaitingOrderTrajectoryClassifier {
    static final double LOADING_RADIUS_KM = 5.0;
    static final int SAMPLE_COUNT = 36;
    private static final double MIN_CLEAR_CHANGE_KM = 1.0;
    private static final double FLAT_DELTA_KM = 0.2;
    private static final double DIRECTION_RATIO = 0.60;
    private static final double EARTH_RADIUS_KM = 6_371.0088;

    private WaitingOrderTrajectoryClassifier() {}

    public static Classification classify(
            double[] currentPosition,
            double[] loadingPosition,
            List<ProviderTrajectoryClient.TrackPoint> rawPoints
    ) {
        Instant start = rawPoints == null || rawPoints.isEmpty() ? null : rawPoints.stream()
                .map(ProviderTrajectoryClient.TrackPoint::time).min(Instant::compareTo).orElse(null);
        Instant end = rawPoints == null || rawPoints.isEmpty() ? null : rawPoints.stream()
                .map(ProviderTrajectoryClient.TrackPoint::time).max(Instant::compareTo).orElse(null);
        return classify(currentPosition, loadingPosition, rawPoints, start, end);
    }

    public static Classification classify(
            double[] currentPosition,
            double[] loadingPosition,
            List<ProviderTrajectoryClient.TrackPoint> rawPoints,
            Instant windowStart,
            Instant windowEnd
    ) {
        if (!valid(currentPosition) || !valid(loadingPosition)) {
            return Classification.unknown("missing-coordinate");
        }
        double currentDistance = distanceKm(currentPosition, loadingPosition);
        if (currentDistance <= LOADING_RADIUS_KM) {
            return new Classification(
                    State.LOADING, "current-position-inside-loading-radius", false,
                    currentDistance, rawPoints == null ? 0 : rawPoints.size(), 0,
                    false, "inside", List.of(currentDistance));
        }
        List<ProviderTrajectoryClient.TrackPoint> sampled =
                downsample(rawPoints, SAMPLE_COUNT, windowStart, windowEnd);
        if (sampled.size() < 2) {
            return Classification.unknown("insufficient-history", currentDistance,
                    rawPoints == null ? 0 : rawPoints.size(), sampled.size());
        }
        List<Double> distances = new ArrayList<>(sampled.size() + 1);
        for (ProviderTrajectoryClient.TrackPoint point : sampled) {
            distances.add(distanceKm(new double[]{point.lng(), point.lat()}, loadingPosition));
        }
        distances.add(currentDistance);

        int lastDwellIndex = lastConsecutiveInsideIndex(distances.subList(0, distances.size() - 1));
        boolean hasDwell = lastDwellIndex >= 1;
        if (hasDwell) {
            List<Double> afterDwell = distances.subList(lastDwellIndex, distances.size());
            Trend departureTrend = trend(afterDwell);
            if (departureTrend == Trend.AWAY
                    && currentDistance - distances.get(lastDwellIndex) >= MIN_CLEAR_CHANGE_KM) {
                return new Classification(
                        State.DEPARTED, "dwelled-near-loading-point-then-moved-away", true,
                        currentDistance, rawPoints == null ? 0 : rawPoints.size(), sampled.size(),
                        true, departureTrend.name(), List.copyOf(distances));
            }
        }

        Trend overallTrend = trend(distances);
        if (overallTrend == Trend.TOWARD) {
            return new Classification(
                    State.TO_LOADING, "distance-to-loading-point-is-decreasing", false,
                    currentDistance, rawPoints == null ? 0 : rawPoints.size(), sampled.size(),
                    hasDwell, overallTrend.name(), List.copyOf(distances));
        }
        return new Classification(
                State.UNKNOWN, hasDwell ? "post-dwell-trend-not-clear" : "trajectory-trend-not-clear", false,
                currentDistance, rawPoints == null ? 0 : rawPoints.size(), sampled.size(),
                hasDwell, overallTrend.name(), List.copyOf(distances));
    }

    static List<ProviderTrajectoryClient.TrackPoint> downsample(
            List<ProviderTrajectoryClient.TrackPoint> rawPoints,
            int count
    ) {
        if (rawPoints == null || rawPoints.isEmpty() || count <= 0) return List.of();
        List<ProviderTrajectoryClient.TrackPoint> sorted = rawPoints.stream()
                .filter(point -> point != null && point.time() != null)
                .sorted(Comparator.comparing(ProviderTrajectoryClient.TrackPoint::time))
                .toList();
        Instant start = sorted.isEmpty() ? null : sorted.get(0).time();
        Instant end = sorted.isEmpty() ? null : sorted.get(sorted.size() - 1).time();
        return downsample(sorted, count, start, end);
    }

    static List<ProviderTrajectoryClient.TrackPoint> downsample(
            List<ProviderTrajectoryClient.TrackPoint> rawPoints,
            int count,
            Instant windowStart,
            Instant windowEnd
    ) {
        if (rawPoints == null || rawPoints.isEmpty() || count <= 0) return List.of();
        List<ProviderTrajectoryClient.TrackPoint> sorted = rawPoints.stream()
                .filter(point -> point != null && point.time() != null)
                .sorted(Comparator.comparing(ProviderTrajectoryClient.TrackPoint::time))
                .toList();
        if (sorted.size() <= count) return sorted;
        long start = windowStart == null ? sorted.get(0).time().toEpochMilli() : windowStart.toEpochMilli();
        long end = windowEnd == null ? sorted.get(sorted.size() - 1).time().toEpochMilli() : windowEnd.toEpochMilli();
        if (end <= start) return sorted.subList(0, Math.min(count, sorted.size()));
        Set<Integer> selectedIndexes = new LinkedHashSet<>();
        int cursor = 0;
        for (int i = 0; i < count; i++) {
            long target = start + Math.round((end - start) * (i / (double) (count - 1)));
            while (cursor + 1 < sorted.size()
                    && distanceInTime(sorted.get(cursor + 1).time(), target)
                    <= distanceInTime(sorted.get(cursor).time(), target)) {
                cursor++;
            }
            selectedIndexes.add(cursor);
        }
        return selectedIndexes.stream().map(sorted::get).toList();
    }

    private static long distanceInTime(Instant time, long target) {
        return Math.abs(time.toEpochMilli() - target);
    }

    private static int lastConsecutiveInsideIndex(List<Double> distances) {
        int result = -1;
        for (int i = 1; i < distances.size(); i++) {
            if (distances.get(i - 1) <= LOADING_RADIUS_KM && distances.get(i) <= LOADING_RADIUS_KM) {
                result = i;
            }
        }
        return result;
    }

    private static Trend trend(List<Double> values) {
        if (values == null || values.size() < 2) return Trend.FLAT;
        double firstMedian = medianWindow(values, true);
        double lastMedian = medianWindow(values, false);
        double net = lastMedian - firstMedian;
        int toward = 0;
        int away = 0;
        for (int i = 1; i < values.size(); i++) {
            double delta = values.get(i) - values.get(i - 1);
            if (delta <= -FLAT_DELTA_KM) toward++;
            else if (delta >= FLAT_DELTA_KM) away++;
        }
        int directional = toward + away;
        double towardRatio = directional == 0 ? 0 : toward / (double) directional;
        double awayRatio = directional == 0 ? 0 : away / (double) directional;
        if (net <= -MIN_CLEAR_CHANGE_KM && towardRatio >= DIRECTION_RATIO) return Trend.TOWARD;
        if (net >= MIN_CLEAR_CHANGE_KM && awayRatio >= DIRECTION_RATIO) return Trend.AWAY;
        return Trend.FLAT;
    }

    private static double medianWindow(List<Double> values, boolean first) {
        int window = Math.max(1, Math.min(5, values.size() / 3));
        List<Double> sample = first
                ? new ArrayList<>(values.subList(0, window))
                : new ArrayList<>(values.subList(values.size() - window, values.size()));
        sample.sort(Double::compareTo);
        int middle = sample.size() / 2;
        return sample.size() % 2 == 1
                ? sample.get(middle)
                : (sample.get(middle - 1) + sample.get(middle)) / 2.0;
    }

    static double distanceKm(double[] left, double[] right) {
        double lat1 = Math.toRadians(left[1]);
        double lat2 = Math.toRadians(right[1]);
        double dLat = lat2 - lat1;
        double dLng = Math.toRadians(right[0] - left[0]);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(lat1) * Math.cos(lat2)
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return EARTH_RADIUS_KM * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    private static boolean valid(double[] coordinate) {
        return coordinate != null && coordinate.length >= 2
                && Double.isFinite(coordinate[0]) && Double.isFinite(coordinate[1])
                && coordinate[0] >= -180 && coordinate[0] <= 180
                && coordinate[1] >= -90 && coordinate[1] <= 90;
    }

    public enum State { TO_LOADING, LOADING, DEPARTED, UNKNOWN }
    enum Trend { TOWARD, AWAY, FLAT }

    public record Classification(
            State state,
            String reason,
            boolean groupEligible,
            double currentDistanceKm,
            int rawPointCount,
            int sampledPointCount,
            boolean consecutiveLoadingDwell,
            String trend,
            List<Double> sampledDistancesKm
    ) {
        static Classification unknown(String reason) {
            return unknown(reason, Double.NaN, 0, 0);
        }

        static Classification unknown(String reason, double distance, int rawCount, int sampledCount) {
            return new Classification(State.UNKNOWN, reason, false, distance,
                    rawCount, sampledCount, false, Trend.FLAT.name(), List.of());
        }
    }
}
