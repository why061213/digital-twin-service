package com.jushen.digitaltwin.townroad;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ExternalOrderRecord(
        String orderId,
        String lineId,
        List<Line> lines,
        Location from,
        Location to,
        Vehicle vehicle,
        String status,
        String updatedAt,
        Boolean deleted,
        Boolean upToDate,
        Integer lineIndex,
        Integer vehicleIndex
) {
    public ExternalOrderRecord(
            String orderId,
            String lineId,
            Location from,
            Location to,
            Vehicle vehicle,
            String status,
            String updatedAt,
            Boolean deleted,
            Boolean upToDate
    ) {
        this(orderId, lineId, null, from, to, vehicle, status, updatedAt, deleted, upToDate, null, null);
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

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Line(
            String lineId,
            Location from,
            Location to,
            Vehicle vehicle,
            List<Vehicle> vehicles,
            String status,
            String updatedAt,
            Boolean deleted,
            Boolean upToDate
    ) {
    }
}
