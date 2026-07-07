package com.jushen.digitaltwin.grouping;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 业务综合分组策略。
 *
 * <p>该策略不是单纯的“按订单”或“按路径”，而是道路大屏的默认展示策略：
 * 先按运输方向形成较高密度的展示组，再把过小的方向桶压缩到综合组中。
 * 组内仍会自动推断 groupScenario，用于告诉前端这组是单订单多路径、多订单同路径、
 * 多订单同方向，还是混合压缩批次。</p>
 */
@Component
public class BusinessPriorityGroupingStrategy implements AdvancedGroupingStrategy {

    @Override
    public String name() {
        return "business-priority";
    }

    @Override
    public String description() {
        return "综合订单、路径、方向信息，优先保证每组信息密度，再输出业务场景。";
    }

    @Override
    public List<GroupSummary> group(List<? extends RouteInfo> routes, GroupingContext context) {
        int groupSize = context.getGroupSize();
        int compactThreshold = Math.max(2, (int) Math.ceil(groupSize * 0.45));
        int orderStandaloneThreshold = Math.max(2, groupSize);
        List<GroupSummary> result = new ArrayList<>();
        List<RouteInfo> compactBuffer = new ArrayList<>();
        int groupIndex = 0;

        Set<RouteInfo> orderGroupedRoutes = new HashSet<>();
        List<Map.Entry<String, List<RouteInfo>>> orderBuckets = new ArrayList<>(
                GroupingUtils.groupBy(routes, GroupingUtils::orderKey).entrySet()
        );
        orderBuckets.sort(
                Comparator.<Map.Entry<String, List<RouteInfo>>>comparingInt((entry) -> entry.getValue().size())
                        .reversed()
                        .thenComparing(Map.Entry::getKey)
        );
        for (Map.Entry<String, List<RouteInfo>> entry : orderBuckets) {
            List<RouteInfo> bucketRoutes = entry.getValue();
            if (bucketRoutes.size() <= 1) {
                continue;
            }
            orderGroupedRoutes.addAll(bucketRoutes);
            if (bucketRoutes.size() < orderStandaloneThreshold) {
                compactBuffer.addAll(bucketRoutes);
                while (compactBuffer.size() >= groupSize) {
                    List<RouteInfo> slice = new ArrayList<>(compactBuffer.subList(0, groupSize));
                    compactBuffer.subList(0, groupSize).clear();
                    result.add(buildBusinessGroup("biz", "综合运输批次", groupIndex++, 1, slice, RouteGroupType.MIXED));
                }
                continue;
            }
            result.add(buildBusinessGroup(
                    "biz-order",
                    "订单 " + entry.getKey(),
                    groupIndex,
                    1,
                    new ArrayList<>(bucketRoutes),
                    RouteGroupType.SAME_ORDER
            ));
            groupIndex++;
        }

        List<RouteInfo> routeCandidates = GroupingUtils.sortedCopy(routes).stream()
                .filter((route) -> !orderGroupedRoutes.contains(route))
                .toList();

        // 业务视角下，地图首先需要“看得清”：同方向车辆优先放在一起，减少小碎组。
        List<Map.Entry<String, List<RouteInfo>>> routeBuckets = new ArrayList<>(
                GroupingUtils.groupBy(routeCandidates, GroupingUtils::routeKey).entrySet()
        );
        routeBuckets.sort(
                Comparator.<Map.Entry<String, List<RouteInfo>>>comparingInt((entry) -> entry.getValue().size())
                        .reversed()
                        .thenComparing(Map.Entry::getKey)
        );

        for (Map.Entry<String, List<RouteInfo>> entry : routeBuckets) {
            List<RouteInfo> bucketRoutes = entry.getValue();
            if (bucketRoutes.size() < compactThreshold) {
                compactBuffer.addAll(bucketRoutes);
                while (compactBuffer.size() >= groupSize) {
                    List<RouteInfo> slice = new ArrayList<>(compactBuffer.subList(0, groupSize));
                    compactBuffer.subList(0, groupSize).clear();
                    result.add(buildBusinessGroup("biz", "综合运输批次", groupIndex++, 1, slice, RouteGroupType.MIXED));
                }
                continue;
            }

            int subIndex = 1;
            for (int start = 0; start < bucketRoutes.size(); start += groupSize) {
                int end = Math.min(bucketRoutes.size(), start + groupSize);
                List<RouteInfo> slice = new ArrayList<>(bucketRoutes.subList(start, end));
                result.add(buildBusinessGroup(
                        "biz-route",
                        entry.getKey(),
                        groupIndex,
                        subIndex++,
                        slice,
                        RouteGroupType.SAME_ROUTE
                ));
            }
            groupIndex++;
        }

        if (!compactBuffer.isEmpty()) {
            result.add(buildBusinessGroup("biz", "综合运输批次", groupIndex, 1, compactBuffer, RouteGroupType.MIXED));
        }

        return result;
    }

    private GroupSummary buildBusinessGroup(
            String prefix,
            String groupKey,
            int groupIndex,
            int subIndex,
            List<RouteInfo> routes,
            RouteGroupType groupType
    ) {
        return GroupingUtils.buildSummary(
                prefix + "-" + (groupIndex + 1) + "-" + subIndex + "-" + GroupingUtils.safeGroupToken(groupKey),
                groupKey,
                groupIndex,
                subIndex,
                routes,
                groupType
        );
    }
}
