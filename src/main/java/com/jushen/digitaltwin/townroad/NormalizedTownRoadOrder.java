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

        /**
         * 该订单在省份路网中的所有等长最短路径。
         * 例如广东到浙江可能同时存在：
         * [440000,350000,330000] 和 [440000,360000,330000]。
         */
        List<List<String>> provincePaths,
        List<String> provincePathKeys,

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
