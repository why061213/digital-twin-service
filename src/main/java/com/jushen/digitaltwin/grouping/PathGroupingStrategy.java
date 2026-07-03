package com.jushen.digitaltwin.grouping;

import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 按完整路径分组。
 *
 * <p>不同订单、不同起终点也可能复用同一条道路路径。该策略会优先使用
 * PathAwareRouteInfo.getPathKey()；如果没有 pathKey，则根据坐标序列生成粗粒度签名。</p>
 */
@Component
public class PathGroupingStrategy implements AdvancedGroupingStrategy {

    @Override
    public String name() {
        return "by-path";
    }

    @Override
    public String description() {
        return "按 pathKey 或坐标路径签名聚合路线。";
    }

    @Override
    public List<GroupSummary> group(List<? extends RouteInfo> routes, GroupingContext context) {
        Map<String, List<RouteInfo>> buckets = GroupingUtils.groupBy(
                routes,
                (route) -> "路径 " + GroupingUtils.pathKey(route)
        );
        return GroupingUtils.splitBuckets(buckets, context.getGroupSize(), "path", RouteGroupType.SAME_PATH);
    }
}
