package com.jushen.digitaltwin.dto;

import java.util.List;
import java.util.Map;

/**
 * 统一路线渲染 DTO。
 * RM1（长途 RoadMap）和未来的 RM2 均使用此结构，
 * 后端数据模型与前端动画模型完全解耦。
 */
public record RenderRouteDTO(
        /** 唯一线路标识（instanceId） */
        String lineId,
        /** 订单号 */
        String orderId,
        /** 车牌 */
        String plate,
        /** 车辆ID（外部接口的 vehicle_id） */
        String carId,
        /** 起点名称 */
        String fromName,
        /** 终点名称 */
        String toName,
        /** 起点坐标 [lng, lat] */
        double[] fromCoords,
        /** 终点坐标 [lng, lat] */
        double[] toCoords,
        /** 路线途经坐标点序列 */
        List<double[]> coordinates,
        /** 路线长度（公里） */
        Double routeLengthKm,
        /** 速度（km/h） */
        Double speedKmh,
        /** 订单状态 */
        String status,
        /** 货物描述 */
        String cargo,
        /** 预计运输时长（毫秒） */
        Long travelDurationMs,
        /** 路径标识（坐标归一化后用于合并重复路径） */
        String pathKey,
        /** 路线类型：road（长途）或 town（短途） */
        String scope,
        /** 附加元数据 */
        Map<String, Object> meta
) {
    public RenderRouteDTO {
        if (lineId == null || lineId.isBlank()) throw new IllegalArgumentException("lineId is required");
        if (coordinates == null) coordinates = List.of();
        if (meta == null) meta = Map.of();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String lineId;
        private String orderId;
        private String plate;
        private String carId;
        private String fromName;
        private String toName;
        private double[] fromCoords;
        private double[] toCoords;
        private List<double[]> coordinates = List.of();
        private Double routeLengthKm;
        private Double speedKmh;
        private String status;
        private String cargo;
        private Long travelDurationMs;
        private String pathKey;
        private String scope = "road";
        private Map<String, Object> meta = Map.of();

        public Builder lineId(String v) { lineId = v; return this; }
        public Builder orderId(String v) { orderId = v; return this; }
        public Builder plate(String v) { plate = v; return this; }
        public Builder carId(String v) { carId = v; return this; }
        public Builder fromName(String v) { fromName = v; return this; }
        public Builder toName(String v) { toName = v; return this; }
        public Builder fromCoords(double[] v) { fromCoords = v; return this; }
        public Builder toCoords(double[] v) { toCoords = v; return this; }
        public Builder coordinates(List<double[]> v) { coordinates = v != null ? v : List.of(); return this; }
        public Builder routeLengthKm(Double v) { routeLengthKm = v; return this; }
        public Builder speedKmh(Double v) { speedKmh = v; return this; }
        public Builder status(String v) { status = v; return this; }
        public Builder cargo(String v) { cargo = v; return this; }
        public Builder travelDurationMs(Long v) { travelDurationMs = v; return this; }
        public Builder pathKey(String v) { pathKey = v; return this; }
        public Builder scope(String v) { scope = v; return this; }
        public Builder meta(Map<String, Object> v) { meta = v != null ? v : Map.of(); return this; }

        public RenderRouteDTO build() {
            return new RenderRouteDTO(lineId, orderId, plate, carId, fromName, toName,
                    fromCoords, toCoords, coordinates, routeLengthKm, speedKmh,
                    status, cargo, travelDurationMs, pathKey, scope, meta);
        }
    }
}
