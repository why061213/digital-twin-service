package com.jushen.digitaltwin.grouping;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 旧版顺序分组策略，保留用于兼容。
 */
@Component
public class SequentialStrategy implements GroupingStrategy {
    @Override
    public String getName() {
        return "sequential";
    }

    @Override
    public List<GroupSummary> group(List<RouteInfo> activeRoutes, int groupSize) {
        int safeGroupSize = Math.max(1, groupSize);
        List<GroupSummary> summaries = new ArrayList<>();
        for (int start = 0; start < activeRoutes.size(); start += safeGroupSize) {
            int end = Math.min(activeRoutes.size(), start + safeGroupSize);
            List<RouteInfo> subList = new ArrayList<>(activeRoutes.subList(start, end));
            String groupId = "group-" + (start / safeGroupSize + 1);
            summaries.add(GroupingUtils.buildSummary(
                    groupId,
                    "全部路线",
                    start / safeGroupSize,
                    start / safeGroupSize + 1,
                    subList,
                    RouteGroupType.SEQUENTIAL
            ));
        }
        return summaries;
    }

    @Override
    public List<RouteInfo> getRoutesByGroup(List<RouteInfo> activeRoutes, int groupSize, String groupId) {
        return group(activeRoutes, groupSize).stream()
                .filter((group) -> group.getGroupId().equals(groupId))
                .findFirst()
                .map(GroupSummary::getRoutes)
                .orElse(Collections.emptyList());
    }
}
