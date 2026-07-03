package com.jushen.digitaltwin.grouping;

import java.util.List;

/**
 * 策略引擎输出对象。
 */
public class RouteGroupingResult {
    private final String strategy;
    private final int groupSize;
    private final int totalRoutes;
    private final List<GroupSummary> groups;

    public RouteGroupingResult(String strategy, int groupSize, int totalRoutes, List<GroupSummary> groups) {
        this.strategy = strategy;
        this.groupSize = groupSize;
        this.totalRoutes = totalRoutes;
        this.groups = groups == null ? List.of() : List.copyOf(groups);
    }

    public String getStrategy() {
        return strategy;
    }

    public int getGroupSize() {
        return groupSize;
    }

    public int getTotalRoutes() {
        return totalRoutes;
    }

    public List<GroupSummary> getGroups() {
        return groups;
    }
}
