package com.jushen.digitaltwin.service;

import java.time.Instant;

/**
 * 车辆位置缓存快照：保存从外部接口获取的真实位置信息。
 * source 取值：real（真实位置）、stale-real（超过保质期的真实位置）、simulated（模拟位置）。
 */
public record PositionSnapshot(
        String lineId,
        String vehicleId,
        String vehicleName,
        String plate,
        double lng,
        double lat,
        double speedKmh,
        String driverName,
        String address,
        String stateStr,
        Integer directionDeg,
        String directionLabel,
        Instant providerTime,
        Instant fetchedAt,
        String source,
        boolean stale
) {
    public double[] position() {
        return new double[]{lng, lat};
    }

    public static PositionSnapshot fromProvider(
            String lineId,
            String vehicleId,
            String vehicleName,
            String plate,
            double lng,
            double lat,
            double speedKmh
    ) {
        return fromProvider(
                lineId, vehicleId, vehicleName, plate, lng, lat, speedKmh,
                null, null, null, null, null
        );
    }

    public static PositionSnapshot fromProvider(
            String lineId,
            String vehicleId,
            String vehicleName,
            String plate,
            double lng,
            double lat,
            double speedKmh,
            String driverName,
            String address,
            String stateStr,
            Integer directionDeg,
            String directionLabel
    ) {
        Instant now = Instant.now();
        return new PositionSnapshot(
                lineId, vehicleId, vehicleName, plate,
                lng, lat, speedKmh,
                driverName, address, stateStr, directionDeg, directionLabel,
                now, now,
                "real",
                false
        );
    }

    public PositionSnapshot markStale() {
        return new PositionSnapshot(
                lineId, vehicleId, vehicleName, plate,
                lng, lat, speedKmh,
                driverName, address, stateStr, directionDeg, directionLabel,
                providerTime, fetchedAt,
                "stale-real",
                true
        );
    }
}
