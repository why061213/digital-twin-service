package com.jushen.digitaltwin.grouping;

import java.util.List;

/**
 * 旧版分组策略接口。
 *
 * <p>该接口保留为兼容层。新分组能力请优先使用 AdvancedGroupingStrategy，
 * 因为新接口支持上下文参数、分组语义和样式提示。</p>
 */
public interface GroupingStrategy {
    /**
     * 策略名称，与配置中的 strategy 对应。
     */
    String getName();

    /**
     * 执行分组。
     *
     * @param activeRoutes 当前活跃路线
     * @param groupSize 每组最大路线数
     * @return 分组摘要列表
     */
    List<GroupSummary> group(List<RouteInfo> activeRoutes, int groupSize);

    /**
     * 根据 groupId 获取该组内的路线列表。
     *
     * @param activeRoutes 当前活跃路线
     * @param groupSize 分组大小
     * @param groupId 组 ID
     * @return 该组内的路线子列表，不存在则返回空列表
     */
    List<RouteInfo> getRoutesByGroup(List<RouteInfo> activeRoutes, int groupSize, String groupId);
}
