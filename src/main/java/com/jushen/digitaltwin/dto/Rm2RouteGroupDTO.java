package com.jushen.digitaltwin.dto;

import java.util.List;
import java.util.Map;

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
        /** 该组业务线路数量，不是车辆数量 */
        int count,
        /** 该组包含的业务线路 ID */
        List<String> orderLineIds,
        /** 该组包含的车辆实例 lineId */
        List<String> vehicleLineIds,
        /** 业务线路 ID -> 车辆实例 lineId */
        Map<String, List<String>> vehicleLineIdsByOrderLineId,
        /** 该组车辆实例数量 */
        int vehicleCount,
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
        if (vehicleLineIds == null) vehicleLineIds = List.of();
        if (vehicleLineIdsByOrderLineId == null) vehicleLineIdsByOrderLineId = Map.of();
    }
}
