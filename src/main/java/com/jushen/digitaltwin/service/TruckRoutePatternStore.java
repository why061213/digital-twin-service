package com.jushen.digitaltwin.service;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** 内存态跨订单替代路线证据；后续可无缝替换成数据库持久化实现。 */
final class TruckRoutePatternStore {
    private final Map<String, Evidence> patterns = new ConcurrentHashMap<>();

    boolean isExpected(
            String baselineSignature, double departureProgress, List<double[]> branch,
            String plate, String orderId) {
        Evidence evidence = patterns.get(key(baselineSignature, departureProgress, branch));
        return evidence != null && (evidence.plates.size() >= 2 || evidence.orders.size() >= 3);
    }

    void recordAlternative(
            String baselineSignature, double departureProgress, List<double[]> branch,
            String plate, String orderId) {
        if (branch == null || branch.size() < 2) return;
        Evidence evidence = patterns.computeIfAbsent(
                key(baselineSignature, departureProgress, branch), ignored -> new Evidence());
        if (plate != null && !plate.isBlank()) evidence.plates.add(plate);
        if (orderId != null && !orderId.isBlank()) evidence.orders.add(orderId);
    }

    private String key(String baselineSignature, double progress, List<double[]> branch) {
        int progressBucket = (int) Math.round(Math.max(0, Math.min(1, progress)) * 20);
        return baselineSignature + "::" + progressBucket + "::" + shapeSignature(branch);
    }

    private String shapeSignature(List<double[]> branch) {
        if (branch == null || branch.size() < 2) return "unknown-shape";
        int[] indexes = {0, branch.size() / 4, branch.size() / 2, branch.size() * 3 / 4, branch.size() - 1};
        StringBuilder result = new StringBuilder();
        for (int index : indexes) {
            double[] point = branch.get(index);
            if (point == null || point.length < 2) continue;
            // 约 1km 网格，允许同一绕行分支的正常定位抖动，但不会把不同支路混成一种模式。
            result.append(String.format(Locale.ROOT, "%.2f,%.2f;", point[0], point[1]));
        }
        return result.toString();
    }

    private static final class Evidence {
        private final Set<String> plates = ConcurrentHashMap.newKeySet();
        private final Set<String> orders = ConcurrentHashMap.newKeySet();
    }
}
