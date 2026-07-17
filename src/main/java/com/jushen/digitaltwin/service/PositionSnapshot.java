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
    private static final double EARTH_RADIUS_KM = 6_371.0088;

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

    /**
     * 以最后一次真实定位为锚点，按供应商速度和方位角推算指定时刻的位置。
     * 原始快照保持不变，避免连续读取产生累计漂移。
     */
    public PositionSnapshot predictAt(Instant predictionTime) {
        if (predictionTime == null || fetchedAt == null || directionDeg == null
                || !Double.isFinite(speedKmh) || speedKmh <= 0 || stale) {
            return this;
        }
        long elapsedMillis = predictionTime.toEpochMilli() - fetchedAt.toEpochMilli();
        if (elapsedMillis <= 0) return this;

        double distanceKm = speedKmh * elapsedMillis / 3_600_000.0;
        double angularDistance = distanceKm / EARTH_RADIUS_KM;
        double bearing = Math.toRadians(((directionDeg % 360) + 360) % 360);
        double startLat = Math.toRadians(lat);
        double startLng = Math.toRadians(lng);

        double predictedLat = Math.asin(
                Math.sin(startLat) * Math.cos(angularDistance)
                        + Math.cos(startLat) * Math.sin(angularDistance) * Math.cos(bearing)
        );
        double predictedLng = startLng + Math.atan2(
                Math.sin(bearing) * Math.sin(angularDistance) * Math.cos(startLat),
                Math.cos(angularDistance) - Math.sin(startLat) * Math.sin(predictedLat)
        );
        double normalizedLng = (Math.toDegrees(predictedLng) + 540) % 360 - 180;

        return new PositionSnapshot(
                lineId, vehicleId, vehicleName, plate,
                normalizedLng, Math.toDegrees(predictedLat), speedKmh,
                driverName, address, stateStr, directionDeg, directionLabel,
                providerTime, fetchedAt, source, stale
        );
    }
}
