package com.jushen.digitaltwin.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * RM2 不可变快照。
 * /groups 和 /groups/{id}/routes 永远来自同一个版本。
 */
public record Rm2Snapshot(
        String snapshotVersion,
        Instant issuedAt,
        List<RenderRouteDTO> routes,
        List<Rm2RouteGroupDTO> groups,
        Rm2ChainStructureDTO chainStructure,
        /** groupId → 该组路线列表 */
        Map<String, List<RenderRouteDTO>> routesByGroupId,
        /** lineId → groupId */
        Map<String, String> groupIdByLineId
) {
    public Rm2Snapshot {
        if (routes == null) routes = List.of();
        if (groups == null) groups = List.of();
        if (chainStructure == null) chainStructure = Rm2ChainStructureDTO.empty();
        if (routesByGroupId == null) routesByGroupId = Map.of();
        if (groupIdByLineId == null) groupIdByLineId = Map.of();
    }
}
