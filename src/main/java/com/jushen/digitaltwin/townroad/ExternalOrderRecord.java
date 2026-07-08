package com.jushen.digitaltwin.townroad;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ExternalOrderRecord(
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
}