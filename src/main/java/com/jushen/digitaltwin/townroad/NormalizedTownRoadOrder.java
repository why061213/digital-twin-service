package com.jushen.digitaltwin.townroad;

import java.util.List;

public record NormalizedTownRoadOrder(
        String orderId,
        String lineId,

        String fromKey,
        String toKey,
        String odKey,

        String fromProvinceKey,
        String toProvinceKey,

        List<String> provincePath,
        String provincePathKey,

        String groupId,
        String groupName,

        ExternalOrderRecord.Location from,
        ExternalOrderRecord.Location to,
        ExternalOrderRecord.Vehicle vehicle,

        String status,
        String updatedAt,
        boolean deleted,
        boolean upToDate,

        String dataSignature,
        String routeSignature
) {
}