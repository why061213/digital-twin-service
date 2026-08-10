package com.jushen.digitaltwin.dto;

import java.util.List;

/**
 * 路线组列表 DTO。前端调用 /api/road/groups 时返回。
 */
public record RouteGroupListDTO(
        /** 分组列表 */
        List<RenderRouteGroupDTO> groups,
        /** 总路线数 */
        int totalRoutes,
        /** 每组大小 */
        int groupSize,
        /** 分组策略 */
        String strategy,
        /** 展示模式：single_source / multi_source_rotation */
        String displayMode,
        /** 差异统计（可选） */
        Object diff
) {
    public RouteGroupListDTO {
        if (groups == null) groups = List.of();
    }
}
