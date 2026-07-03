package com.jushen.digitaltwin.grouping;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 按时间窗口分组。
 *
 * <p>根据路线开始时间 startTime 划入固定时间窗口，窗口大小由
 * GroupingContext.timeWindowMillis 控制。适合观察最近一段时间发起的运输批次。</p>
 */
@Component
public class TimeWindowGroupingStrategy implements AdvancedGroupingStrategy {
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter
            .ofPattern("MM-dd HH:mm")
            .withZone(ZoneId.systemDefault());

    @Override
    public String name() {
        return "by-time-window";
    }

    @Override
    public String description() {
        return "按 startTime 落入的固定时间窗口聚合路线。";
    }

    @Override
    public List<GroupSummary> group(List<? extends RouteInfo> routes, GroupingContext context) {
        long windowMillis = context.getTimeWindowMillis();
        Map<String, List<RouteInfo>> buckets = GroupingUtils.groupBy(routes, (route) -> {
            long windowStart = route.getStartTime() / windowMillis * windowMillis;
            long windowEnd = windowStart + windowMillis;
            return FORMATTER.format(Instant.ofEpochMilli(windowStart))
                    + " - "
                    + FORMATTER.format(Instant.ofEpochMilli(windowEnd));
        });
        return GroupingUtils.splitBuckets(buckets, context.getGroupSize(), "time", RouteGroupType.TIME_WINDOW);
    }
}
