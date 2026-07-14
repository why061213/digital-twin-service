package com.jushen.digitaltwin.dto;

/**
 * 车辆位置 DTO。
 */
public record VehiclePositionDTO(
        /** 线路标识 */
        String lineId,
        /** 位置 [lng, lat] */
        double[] position,
        /** 速度 km/h */
        double speedKmh,
        /** 状态 */
        String status,
        /** 位置来源：real / stale-real / simulated */
        String source,
        /** 是否过期 */
        boolean stale,
        /** 数据获取时间 */
        String fetchedAt,
        /** 车辆 ID（外部接口） */
        String vehicleId,
        /** 车牌 */
        String plate,
        /** 进度 0~1 */
        Double progress
) {
    public VehiclePositionDTO {
        if (position == null) position = new double[]{0, 0};
    }
}
