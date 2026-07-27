package com.jushen.digitaltwin.routeanalysis;

import java.util.List;

/** Backend-owned route semantics consumed directly by renderers. */
public record RouteAnalysisDTO(
        String analysisVersion,
        double totalLengthM,
        List<RoutePartDTO> parts
) {
    public record RoutePartDTO(
            String partId,
            double fromMeasureM,
            double toMeasureM,
            String routeRole,
            List<double[]> coordinates,
            String sharedGroupId,
            String branchGroupId,
            List<SharedRouteRefDTO> sharedWith
    ) {}

    public record SharedRouteRefDTO(
            String lineId,
            String visualKey,
            String orderId,
            String plate
    ) {}
}
