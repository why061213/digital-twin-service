package com.jushen.digitaltwin.service;

import com.jushen.digitaltwin.dto.RenderRouteDTO;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Makes one self-consistent route object for RM2 initial rendering. */
final class Rm2RouteResponseAssembler {
    private Rm2RouteResponseAssembler() {
    }

    static Map<String, Object> effectiveRoute(RenderRouteDTO baseline, Map<String, Object> position) {
        Map<String, Object> result = new LinkedHashMap<>();
        boolean corrected = validCoordinates(position == null ? null : position.get("routeCoordinates"));

        result.put("lineId", baseline.lineId());
        result.put("orderId", baseline.orderId());
        result.put("businessLineId", baseline.businessLineId());
        result.put("plate", baseline.plate());
        result.put("vehicleId", baseline.vehicleId());
        result.put("from", baseline.from());
        result.put("to", baseline.to());
        result.put("fromCoords", baseline.fromCoords());
        result.put("toCoords", baseline.toCoords());
        result.put("coordinates", corrected ? position.get("routeCoordinates") : baseline.coordinates());
        result.put("baselineCoordinates", baseline.coordinates());
        result.put("routeLengthKm", corrected
                ? position.getOrDefault("routeLengthKm", baseline.routeLengthKm())
                : baseline.routeLengthKm());
        result.put("speedKmh", baseline.speedKmh());
        result.put("status", baseline.status());
        result.put("cargo", baseline.cargo());
        result.put("cargoWeight", baseline.cargoWeight());
        result.put("cargoUnit", baseline.cargoUnit());
        result.put("travelDurationMs", corrected
                ? position.getOrDefault("travelDurationMs", baseline.travelDurationMs())
                : baseline.travelDurationMs());
        result.put("pathKey", corrected
                ? position.getOrDefault("pathKey", "vehicle-route::" + baseline.lineId())
                : baseline.pathKey());
        result.put("baselinePathKey", baseline.pathKey());
        result.put("scope", baseline.scope());
        result.put("groupId", baseline.groupId());
        result.put("role", baseline.role());
        result.put("coordinateSystem", baseline.coordinateSystem());
        result.put("updatedAt", baseline.updatedAt());
        result.put("routeSignature", corrected
                ? baseline.routeSignature() + "::revision-" + position.get("routeRevision")
                : baseline.routeSignature());
        result.put("meta", baseline.meta());
        if (corrected) {
            result.put("routeRevision", position.get("routeRevision"));
            result.put("deviationCoordinates", position.getOrDefault("deviationCoordinates", List.of()));
            result.put("traversedCoordinates", position.getOrDefault("traversedCoordinates", List.of()));
            result.put("remainingCoordinates", position.getOrDefault("remainingCoordinates", List.of()));
            result.put("routeReplanAnchors", position.getOrDefault("routeReplanAnchors", List.of()));
            result.put("colorKey", position.get("colorKey"));
            result.put("isRouteBranch", Boolean.TRUE.equals(position.get("isRouteBranch")));
        }
        return result;
    }

    private static boolean validCoordinates(Object value) {
        return value instanceof List<?> coordinates && coordinates.size() >= 2;
    }
}
