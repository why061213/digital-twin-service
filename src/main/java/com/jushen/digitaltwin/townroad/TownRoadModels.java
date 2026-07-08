package com.jushen.digitaltwin.townroad;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Map;

public final class TownRoadModels {

    private TownRoadModels() {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TownRoadRenderCommand(
            String type,
            String commandId,
            String title,
            String description,
            List<String> renderProvinces,
            List<TownRoadOrder> orders,
            String issuedAt
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TownRoadOrder(
            String orderId,
            String lineId,
            String groupId,
            String groupName,
            Location from,
            Location to,
            Vehicle vehicle,
            String status,
            String updatedAt,
            Boolean deleted,
            Boolean upToDate
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Location(
            String name,
            String province,
            String city,
            String district,
            String adcode,
            double[] coords
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Vehicle(
            String plate,
            String carId,
            Double cargoWeight,
            String cargoUnit,
            double[] currentCoords,
            Double speedKmh
    ) {
    }

    public record LocationNode(
            String key,
            String name,
            String province,
            String city,
            String district,
            String adcode,
            String provinceKey,
            double[] coords
    ) {
    }

    public record NormalizedTownRoadOrder(
            String orderId,
            String lineId,
            String groupId,
            String groupName,

            String fromKey,
            String toKey,
            String odKey,

            LocationNode from,
            LocationNode to,

            Vehicle vehicle,

            String status,
            String updatedAt,
            boolean deleted,
            boolean upToDate,

            String dataSignature,
            String routeSignature
    ) {
    }

    public record TownRoadDiff(
            List<NormalizedTownRoadOrder> added,
            List<NormalizedTownRoadOrder> updated,
            List<NormalizedTownRoadOrder> deleted,
            List<NormalizedTownRoadOrder> unchanged,
            List<NormalizedTownRoadOrder> routeChanged,
            List<NormalizedTownRoadOrder> removedFromRender
    ) {
        public Map<String, Object> summary() {
            return Map.of(
                    "added", added.size(),
                    "updated", updated.size(),
                    "deleted", deleted.size(),
                    "unchanged", unchanged.size(),
                    "routeChanged", routeChanged.size(),
                    "removedFromRender", removedFromRender.size()
            );
        }
    }

    public record TownRoadRenderState(
            String commandId,
            String title,
            String description,
            List<String> renderProvinces,
            List<NormalizedTownRoadOrder> activeOrders,
            String issuedAt
    ) {
    }
}