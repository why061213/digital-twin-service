package com.jushen.digitaltwin.townroad;

import java.util.List;

public record TownRoadRenderCommand(
        String type,
        String commandId,
        String title,
        String description,
        List<String> renderProvinces,
        List<TownRoadOrder> orders,
        String issuedAt
) {
    public record TownRoadOrder(
            String orderId,
            String lineId,
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