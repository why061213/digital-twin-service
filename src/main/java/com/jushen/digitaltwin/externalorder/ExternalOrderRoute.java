package com.jushen.digitaltwin.externalorder;

import com.jushen.digitaltwin.grouping.OrderAwareRouteInfo;
import com.jushen.digitaltwin.grouping.PathAwareRouteInfo;

import java.util.List;

public record ExternalOrderRoute(
        String orderId,
        String orderFamilyId,
        String lineId,

        String fromKey,
        String toKey,

        String from,
        String to,

        double[] fromCoords,
        double[] toCoords,

        List<double[]> coordinates,

        String pathKey,
        List<String> segmentKeys,

        String plate,
        String carId,

        Double cargoWeight,
        String cargoUnit,

        String status,
        String updatedAt,

        double speedKmh,
        double routeLengthKm,
        long travelDurationMs,

        double[] currentCoords,

        String dataSignature,
        String routeSignature
) implements OrderAwareRouteInfo, PathAwareRouteInfo {

    @Override
    public String getOrderId() {
        return orderId;
    }

    @Override
    public String getOrderFamilyId() {
        return orderId == null || orderId.isBlank() ? lineId : orderId;
    }

    @Override
    public String getLineId() {
        return lineId;
    }

    @Override
    public String getFrom() {
        return from;
    }

    @Override
    public String getTo() {
        return to;
    }

    @Override
    public double[] getFromCoords() {
        return fromCoords;
    }

    @Override
    public double[] getToCoords() {
        return toCoords;
    }

    @Override
    public double getRouteLengthKm() {
        return routeLengthKm;
    }

    @Override
    public double getSpeedKmh() {
        return speedKmh;
    }

    @Override
    public long getTravelDurationMs() {
        return travelDurationMs;
    }

    @Override
    public long getStartTime() {
        return 0;
    }

    @Override
    public List<double[]> getCoordinates() {
        return coordinates == null ? List.of() : coordinates;
    }

    @Override
    public String getPathKey() {
        return pathKey;
    }

    @Override
    public List<String> getSegmentKeys() {
        return segmentKeys == null ? List.of() : segmentKeys;
    }
}