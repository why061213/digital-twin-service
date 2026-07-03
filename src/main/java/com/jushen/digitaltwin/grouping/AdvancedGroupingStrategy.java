package com.jushen.digitaltwin.grouping;

import java.util.List;

/**
 * 可扩展路线分组策略接口。
 *
 * <p>该接口只依赖 RouteInfo，不依赖 RoutePushService、Controller 或数据库。
 * 后续接入时，现有接口只需要把活跃路线转换为 RouteInfo 列表，再交给策略引擎即可。</p>
 */
public interface AdvancedGroupingStrategy {

    /**
     * 策略名称。用于配置文件和 API 临时切换，例如 sequential、by-route、by-order。
     */
    String name();

    /**
     * 策略说明，便于调试接口或后台页面展示。
     */
    String description();

    /**
     * 执行分组。
     *
     * @param routes 当前仍在运输中的路线，调用方应保证同一批数据视角一致
     * @param context 分组上下文，包含 groupSize、时间窗口等参数
     * @return 分组摘要。GroupSummary.routes 可用于后续 /groups/{id}/routes 返回详情
     */
    List<GroupSummary> group(List<? extends RouteInfo> routes, GroupingContext context);
}
