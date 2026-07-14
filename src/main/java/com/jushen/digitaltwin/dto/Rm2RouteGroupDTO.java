package com.jushen.digitaltwin.dto;

import java.util.List;

/**
 * RM2 路线组 DTO。
 * 精简版，只包含前端 RM2 需要的字段。
 */
public record Rm2RouteGroupDTO(
        /** 稳定分组 ID，如 "rm2:440000:440000:hash:page-1" */
        String groupId,
        /** 分组显示名称 */
        String groupName,
        /** 分组序号（从 0 开始） */
        int index,
        /** 该组路线数量 */
        int count,
        /** 该组包含的 lineId 列表 */
        List<String> lineIds,
        /** 地图 key = fromProvinceKey */
        String mapKey,
        /** 分组场景：same_province / cross_province */
        String groupScenario,
        /** 路径 key */
        String pathKey
) {
    public Rm2RouteGroupDTO {
        if (lineIds == null) lineIds = List.of();
    }
}
