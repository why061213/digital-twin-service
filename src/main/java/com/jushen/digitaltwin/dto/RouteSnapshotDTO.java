package com.jushen.digitaltwin.dto;

import java.util.List;
import java.util.Map;

/**
 * 路线快照 DTO。完整的数据快照，供前端一次性加载。
 */
public record RouteSnapshotDTO(
        /** 快照标识 */
        String snapshotId,
        /** 标题 */
        String title,
        /** 描述 */
        String description,
        /** 需要渲染的省份 adcode 列表 */
        List<String> renderProvinces,
        /** 所有路线 */
        List<RenderRouteDTO> routes,
        /** 分组信息 */
        List<RenderRouteGroupDTO> routeGroups,
        /** 生成时间 */
        String issuedAt,
        /** 差异统计 */
        Map<String, Object> diff
) {
    public RouteSnapshotDTO {
        if (renderProvinces == null) renderProvinces = List.of();
        if (routes == null) routes = List.of();
        if (routeGroups == null) routeGroups = List.of();
        if (diff == null) diff = Map.of();
    }
}
