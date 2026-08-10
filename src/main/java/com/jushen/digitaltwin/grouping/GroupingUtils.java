package com.jushen.digitaltwin.grouping;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * 分组策略公共工具。
 */
final class GroupingUtils {

    private GroupingUtils() {
    }

    static List<RouteInfo> sortedCopy(List<? extends RouteInfo> routes) {
        return routes.stream()
                .map(RouteInfo.class::cast)
                .sorted(Comparator.comparingLong(RouteInfo::getStartTime)
                        .thenComparing(RouteInfo::getLineId))
                .toList();
    }

    static String routeKey(RouteInfo route) {
        return safeText(route.getFrom(), "未知起点") + "→" + safeText(route.getTo(), "未知终点");
    }

    static String orderKey(RouteInfo route) {
        if (route instanceof OrderAwareRouteInfo orderAware) {
            String orderFamilyId = orderAware.getOrderFamilyId();
            if (orderFamilyId != null && !orderFamilyId.isBlank()) {
                return orderFamilyId;
            }
            String orderId = orderAware.getOrderId();
            if (orderId != null && !orderId.isBlank()) {
                return orderId;
            }
        }
        return safeText(route.getLineId(), "unknown-order");
    }

    static String pathKey(RouteInfo route) {
        if (route instanceof PathAwareRouteInfo pathAware) {
            String pathKey = pathAware.getPathKey();
            if (pathKey != null && !pathKey.isBlank()) {
                return pathKey;
            }
        }

        // 没有外部路径 ID 时，用坐标序列生成一个稳定的粗粒度路径签名。
        // 坐标保留 4 位小数，避免浮点细微抖动导致同一路径被拆成多个组。
        List<double[]> coordinates = route.getCoordinates();
        if (coordinates == null || coordinates.isEmpty()) {
            return routeKey(route);
        }
        StringBuilder builder = new StringBuilder();
        for (double[] coordinate : coordinates) {
            if (coordinate == null || coordinate.length < 2) {
                continue;
            }
            if (!builder.isEmpty()) {
                builder.append('|');
            }
            builder.append(roundCoordinate(coordinate[0]))
                    .append(',')
                    .append(roundCoordinate(coordinate[1]));
        }
        return builder.isEmpty() ? routeKey(route) : builder.toString();
    }

    static List<String> segmentKeys(RouteInfo route) {
        if (route instanceof PathAwareRouteInfo pathAware) {
            List<String> segmentKeys = pathAware.getSegmentKeys();
            if (segmentKeys != null && !segmentKeys.isEmpty()) {
                return segmentKeys;
            }
        }

        List<double[]> coordinates = route.getCoordinates();
        if (coordinates == null || coordinates.size() < 2) {
            return List.of();
        }

        List<String> segments = new ArrayList<>();
        for (int i = 1; i < coordinates.size(); i++) {
            double[] prev = coordinates.get(i - 1);
            double[] current = coordinates.get(i);
            if (prev == null || current == null || prev.length < 2 || current.length < 2) {
                continue;
            }
            segments.add(roundCoordinate(prev[0]) + "," + roundCoordinate(prev[1])
                    + "->"
                    + roundCoordinate(current[0]) + "," + roundCoordinate(current[1]));
        }
        return segments;
    }

    static String safeText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    static String safeGroupToken(String value) {
        return safeText(value, "unknown")
                .replace("→", "-to-")
                .replaceAll("[^\\p{IsAlphabetic}\\p{IsDigit}_-]+", "-")
                .replaceAll("-+", "-")
                .replaceAll("^-|-$", "");
    }

    static String stableHash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(safeText(value, "unknown").getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(12);
            for (int i = 0; i < 6; i++) {
                result.append(String.format("%02x", digest[i]));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required for stable route grouping", exception);
        }
    }

    static Map<String, List<RouteInfo>> groupBy(
            List<? extends RouteInfo> routes,
            Function<RouteInfo, String> keySelector
    ) {
        Map<String, List<RouteInfo>> grouped = new LinkedHashMap<>();
        sortedCopy(routes).forEach((route) -> {
            String key = keySelector.apply(route);
            grouped.computeIfAbsent(key, ignored -> new ArrayList<>()).add(route);
        });
        return grouped;
    }

    static List<GroupSummary> splitBuckets(
            Map<String, List<RouteInfo>> buckets,
            int groupSize,
            String idPrefix,
            RouteGroupType groupType
    ) {
        List<GroupSummary> summaries = new ArrayList<>();
        int mainIndex = 0;
        for (Map.Entry<String, List<RouteInfo>> entry : buckets.entrySet()) {
            List<RouteInfo> bucketRoutes = entry.getValue();
            int subIndex = 1;
            for (int start = 0; start < bucketRoutes.size(); start += groupSize) {
                int end = Math.min(bucketRoutes.size(), start + groupSize);
                List<RouteInfo> subList = new ArrayList<>(bucketRoutes.subList(start, end));
                String token = safeGroupToken(entry.getKey());
                String groupId = idPrefix + "-" + (mainIndex + 1) + "-" + subIndex + "-" + token;
                summaries.add(buildSummary(
                        groupId,
                        entry.getKey(),
                        mainIndex,
                        subIndex,
                        subList,
                        groupType
                ));
                subIndex++;
            }
            mainIndex++;
        }
        return summaries;
    }

    static List<GroupSummary> splitBuckets(
            Map<String, List<RouteInfo>> buckets,
            int groupSize,
            String idPrefix
    ) {
        return splitBuckets(buckets, groupSize, idPrefix, RouteGroupType.MIXED);
    }

    static GroupSummary buildSummary(
            String groupId,
            String groupKey,
            int index,
            int subIndex,
            List<RouteInfo> routes,
            RouteGroupType groupType
    ) {
        List<String> orderIds = collectOrderIds(routes);
        String routeKey = collectSingleValue(routes, GroupingUtils::routeKey);
        String pathKey = collectSingleValue(routes, GroupingUtils::pathKey);
        List<String> segmentKeys = collectSegmentKeys(routes);
        RouteGroupScenario scenario = inferScenario(routes, groupType, orderIds, routeKey, pathKey);

        return new GroupSummary(
                groupId,
                groupKey,
                index,
                subIndex,
                routes.size(),
                routes,
                groupType,
                scenario,
                scenario.getDescription(),
                DisplayTemplate.of(scenario),
                RouteGroupStyleHint.of(scenario),
                orderIds,
                routeKey,
                pathKey,
                segmentKeys
        );
    }

    private static RouteGroupScenario inferScenario(
            List<RouteInfo> routes,
            RouteGroupType groupType,
            List<String> orderIds,
            String routeKey,
            String pathKey
    ) {
        if (routes == null || routes.isEmpty()) {
            return RouteGroupScenario.MIXED;
        }
        if (groupType == RouteGroupType.TIME_WINDOW) {
            return RouteGroupScenario.TIME_BATCH;
        }
        if (groupType == RouteGroupType.SEQUENTIAL) {
            return RouteGroupScenario.SEQUENTIAL;
        }

        int orderCount = orderIds.size();
        int routeCount = countDistinct(routes, GroupingUtils::routeKey);
        int pathCount = countDistinct(routes, GroupingUtils::pathKey);
        boolean singleRoute = routeKey != null || routeCount == 1;
        boolean singlePath = pathKey != null || pathCount == 1;

        if (orderCount == 1 && singlePath) {
            return RouteGroupScenario.SINGLE_ORDER_SINGLE_PATH;
        }
        if (orderCount == 1) {
            return RouteGroupScenario.SINGLE_ORDER_MULTI_PATH;
        }
        if (orderCount > 1 && singlePath) {
            return RouteGroupScenario.MULTI_ORDER_SAME_PATH;
        }
        if (orderCount > 1 && singleRoute && pathCount > 1) {
            return RouteGroupScenario.SAME_ROUTE_MULTI_PATH;
        }
        if (orderCount > 1 && singleRoute) {
            return RouteGroupScenario.MULTI_ORDER_SAME_ROUTE;
        }
        return RouteGroupScenario.MIXED;
    }

    private static int countDistinct(List<RouteInfo> routes, Function<RouteInfo, String> selector) {
        Set<String> values = new LinkedHashSet<>();
        for (RouteInfo route : routes) {
            values.add(selector.apply(route));
        }
        return values.size();
    }

    private static List<String> collectOrderIds(List<RouteInfo> routes) {
        Set<String> orderIds = new LinkedHashSet<>();
        for (RouteInfo route : routes) {
            orderIds.add(orderKey(route));
        }
        return List.copyOf(orderIds);
    }

    private static String collectSingleValue(List<RouteInfo> routes, Function<RouteInfo, String> selector) {
        Set<String> values = new LinkedHashSet<>();
        for (RouteInfo route : routes) {
            values.add(selector.apply(route));
        }
        return values.size() == 1 ? values.iterator().next() : null;
    }

    private static List<String> collectSegmentKeys(List<RouteInfo> routes) {
        Set<String> segments = new LinkedHashSet<>();
        for (RouteInfo route : routes) {
            segments.addAll(segmentKeys(route));
        }
        return List.copyOf(segments);
    }

    private static String roundCoordinate(double value) {
        return BigDecimal.valueOf(value)
                .setScale(4, RoundingMode.HALF_UP)
                .stripTrailingZeros()
                .toPlainString();
    }
}
