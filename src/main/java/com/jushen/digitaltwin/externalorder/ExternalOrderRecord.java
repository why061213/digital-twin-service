package com.jushen.digitaltwin.externalorder;

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
    public record Location(
            String name,
            String province,
            String city,
            String district,
            String adcode,
            double[] coords
    ) {}

    public record Vehicle(
            String plate,
            String carId,
            Double cargoWeight,
            String cargoUnit,
            double[] currentCoords,
            Double speedKmh
    ) {}
}