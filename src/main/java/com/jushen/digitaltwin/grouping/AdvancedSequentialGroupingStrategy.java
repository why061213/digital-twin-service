package com.jushen.digitaltwin.grouping;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 顺序分组策略。
 *
 * <p>这是现有行为的等价策略：按路线开始时间排序，每 groupSize 条切成一组。
 * 适合作为兼容兜底，不承担复杂业务语义。</p>
 */
@Component
public class AdvancedSequentialGroupingStrategy implements AdvancedGroupingStrategy {

    @Override
    public String name() {
        return "sequential";
    }

    @Override
    public String description() {
        return "按开始时间顺序切分路线，每组最多 groupSize 条。";
    }

    @Override
    public List<GroupSummary> group(List<? extends RouteInfo> routes, GroupingContext context) {
        Map<String, List<RouteInfo>> bucket = new LinkedHashMap<>();
        bucket.put("全部路线", GroupingUtils.sortedCopy(routes));
        return GroupingUtils.splitBuckets(bucket, context.getGroupSize(), "group", RouteGroupType.SEQUENTIAL);
    }
}
