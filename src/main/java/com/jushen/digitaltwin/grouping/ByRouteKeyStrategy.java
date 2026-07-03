package com.jushen.digitaltwin.grouping;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * 旧版按起终点分组策略，保留用于兼容。
 */
@Component
public class ByRouteKeyStrategy implements GroupingStrategy {

    @Override
    public String getName() {
        return "by-route";
    }

    @Override
    public List<GroupSummary> group(List<RouteInfo> activeRoutes, int groupSize) {
        Map<String, List<RouteInfo>> grouped = GroupingUtils.groupBy(activeRoutes, GroupingUtils::routeKey);
        return GroupingUtils.splitBuckets(
                grouped,
                Math.max(1, groupSize),
                "group",
                RouteGroupType.SAME_ROUTE
        );
    }

    @Override
    public List<RouteInfo> getRoutesByGroup(List<RouteInfo> activeRoutes, int groupSize, String groupId) {
        return group(activeRoutes, groupSize).stream()
                .filter((group) -> group.getGroupId().equals(groupId))
                .findFirst()
                .map(GroupSummary::getRoutes)
                .map((routes) -> routes.stream().map(RouteInfo.class::cast).collect(Collectors.toList()))
                .orElse(Collections.emptyList());
    }
}
