package com.jushen.digitaltwin.grouping;

/**
 * 分组上下文。
 *
 * <p>将 groupSize、策略名、时间窗口等运行参数集中在一个对象里，
 * 后续新增策略参数时不用反复修改策略接口签名。</p>
 */
public class GroupingContext {
    private final String strategyName;
    private final int groupSize;
    private final long timeWindowMillis;
    private final long nowMillis;

    public GroupingContext(String strategyName, int groupSize, long timeWindowMillis, long nowMillis) {
        this.strategyName = strategyName == null || strategyName.isBlank() ? "business-priority" : strategyName;
        this.groupSize = Math.max(1, groupSize);
        this.timeWindowMillis = Math.max(60_000L, timeWindowMillis);
        this.nowMillis = nowMillis > 0 ? nowMillis : System.currentTimeMillis();
    }

    public static GroupingContext defaults(String strategyName, int groupSize) {
        return new GroupingContext(strategyName, groupSize, 15 * 60_000L, System.currentTimeMillis());
    }

    public String getStrategyName() {
        return strategyName;
    }

    public int getGroupSize() {
        return groupSize;
    }

    public long getTimeWindowMillis() {
        return timeWindowMillis;
    }

    public long getNowMillis() {
        return nowMillis;
    }
}
