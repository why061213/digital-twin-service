package com.jushen.digitaltwin.townroad;

import java.util.List;

public record TownRoadRenderCommand(
        String type,
        String commandId,
        String title,
        String description,

        ProvinceRef sourceProvince,

        List<String> renderProvinces,
        List<TownRoadRouteGroup> routeGroups,
        List<TownRoadRouteGroup> displayRouteGroups,
        List<ProvinceEdgeView> provinceEdges,
        List<TownRoadOrder> orders,

        String issuedAt
) {
    public record ProvinceRef(
            String provinceKey,
            String provinceName
    ) {
    }

    public record TownRoadRouteGroup(
            String groupId,
            String groupName,
            String fromProvinceKey,
            String fromProvinceName,
            String toProvinceKey,
            String toProvinceName,
            List<String> primaryOrderLineIds,
            List<String> alongOrderLineIds,
            List<ProvincePathCandidate> candidatePaths,
            Boolean display,
            Boolean absorbed,
            List<String> absorbedByGroupIds,
            String absorbedReason
    ) {
    }

    public record ProvincePathCandidate(
            String pathId,
            List<String> provincePath,
            List<String> provinceNames,
            List<String> edgeKeys,
            Integer pathCost,
            Boolean bestPath,
            List<String> primaryOrderLineIds,
            List<String> alongOrderLineIds
    ) {
    }

    public record ProvinceEdgeView(
            String edgeKey,
            String fromProvinceKey,
            String fromProvinceName,
            String toProvinceKey,
            String toProvinceName,
            List<String> routeGroupIds,
            List<String> pathIds,
            List<String> primaryOrderLineIds,
            List<String> alongOrderLineIds,
            List<String> orderLineIds,
            int orderCount
    ) {
    }

    public record TownRoadOrder(
            String orderId,
            String lineId,
            String sourceLineId,
            String instanceId,
            String vehicleKey,
            String groupId,
            String groupName,
            ExternalOrderRecord.Location from,
            ExternalOrderRecord.Location to,
            ExternalOrderRecord.Vehicle vehicle,
            String status,
            String updatedAt,
            Boolean deleted
    ) {
    }
}
