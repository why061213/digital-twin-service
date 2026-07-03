package com.jushen.digitaltwin.grouping;

import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 按起点和终点分组。
 *
 * <p>路线键为 from→to。同一运输方向的路线聚合在一起，超过 groupSize 时自动拆子组。
 * 适合观察“某两个城市之间有多少车在跑”。</p>
 */
@Component
public class RouteKeyGroupingStrategy implements AdvancedGroupingStrategy {

    @Override
    public String name() {
        return "by-route";
    }

    @Override
    public String description() {
        return "按 from→to 聚合路线，并按 groupSize 自动拆分子组。";
    }

    @Override
    public List<GroupSummary> group(List<? extends RouteInfo> routes, GroupingContext context) {
        Map<String, List<RouteInfo>> buckets = GroupingUtils.groupBy(routes, GroupingUtils::routeKey);
        return GroupingUtils.splitBuckets(buckets, context.getGroupSize(), "route", RouteGroupType.SAME_ROUTE);
    }
}
