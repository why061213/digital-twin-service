package com.jushen.digitaltwin.dto;

/**
 * 车辆位置 DTO。
 */
public record VehiclePositionDTO(
        /** 线路标识 */
        String lineId,
        /** 线路展示域：rm1 / rm2 */
        String scope,
        /** 当前快照内的展示分组 */
        String groupId,
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
        /** 速度质量：provider / calculated / fallback / rejected */
        String speedQuality,
        /** 单调递增的位置帧序列 */
        long sequence,
        /** 进度 0~1 */
        Double progress
) {
    public VehiclePositionDTO {
        if (position == null) position = new double[]{0, 0};
    }
}
