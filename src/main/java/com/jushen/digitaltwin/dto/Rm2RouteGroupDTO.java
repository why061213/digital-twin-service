package com.jushen.digitaltwin.dto;

import java.util.List;

/**
 * RM2 路线组 DTO。
 * 精简版，只包含前端 RM2 需要的字段。
 */
public record Rm2RouteGroupDTO(
        /** 稳定叶组 ID，如 "rm2:440000:440000:page-1" */
        String groupId,
        /** 分组显示名称 */
        String groupName,
        /** 分组序号（从 0 开始） */
        int index,
        /** 该组路线数量 */
        int count,
        /** 该组包含的 lineId 列表 */
        List<String> orderLineIds,
        /** 地图 key = fromProvinceKey */
        String mapKey,
        /** 省份环节点 key */
        String fromProvinceKey,
        /** 方向终点省份 key */
        String toProvinceKey,
        /** 方向环节点 key */
        String directionKey,
        /** 方向下的细分组页码（从 0 开始） */
        int pageIndex,
        /** 分组场景：same_province / cross_province */
        String groupScenario,
        /** 路径 key */
        String pathKey
) {
    public Rm2RouteGroupDTO {
        if (orderLineIds == null) orderLineIds = List.of();
    }
}
