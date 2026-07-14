package com.jushen.digitaltwin.dto;

import java.util.List;

/**
 * 路线展示分组 DTO。
 * 用于前端按组切换展示，每组包含多条路线。
 */
public record RenderRouteGroupDTO(
        /** 稳定分组 ID，如 "road:440000:350000:page-1" */
        String groupId,
        /** 分组显示名称 */
        String groupName,
        /** 分组序号（从 0 开始） */
        int index,
        /** 子序号（同 index 内的排序） */
        int subIndex,
        /** 该组路线数量 */
        int count,
        /** 该组包含的 lineId 列表 */
        List<String> orderLineIds,
        /** 分组场景类型：cross_province / same_province / mixed */
        String groupScenario,
        /** 分组原因描述 */
        String scenarioReason,
        /** 分组类型 */
        String groupType,
        /** 路线方向 key（如 "广东→浙江"） */
        String routeKey,
        /** 路径 key（用于合并相同路径） */
        String pathKey
) {
    public RenderRouteGroupDTO {
        if (orderLineIds == null) orderLineIds = List.of();
    }
}
