package com.jushen.digitaltwin.dto;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Map;
import java.util.HexFormat;

/**
 * 统一路线渲染 DTO。
 * scope: "rm1" = 长途 RoadMap, "rm2" = 短途。
 */
public record RenderRouteDTO(
        /** 唯一线路标识（instanceId） */
        String lineId,
        /** 订单号 */
        String orderId,
        /** 订单内业务线路标识；同线路的多辆车共享该值 */
        String businessLineId,
        /** 车牌 */
        String plate,
        /** 真实供应商车辆ID */
        String vehicleId,

        /** 起点名称 */
        String from,
        /** 终点名称 */
        String to,
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

        /** 稳定路径标识 */
        String pathKey,
        /** rm1 或 rm2 */
        String scope,
        /** 所属分组ID */
        String groupId,
        /** primary 或 along */
        String role,

        /** 坐标系，如 GCJ02 / WGS84 */
        String coordinateSystem,
        /** 最后更新时间 */
        String updatedAt,
        /** 路线签名（坐标+路径的稳定hash） */
        String routeSignature,
        /** 附加元数据 */
        Map<String, Object> meta
) {
    public RenderRouteDTO {
        if (lineId == null || lineId.isBlank()) throw new IllegalArgumentException("lineId is required");
        if (coordinates == null) coordinates = List.of();
        if (meta == null) meta = Map.of();
        if (role == null || role.isBlank()) role = "primary";
        if (coordinateSystem == null || coordinateSystem.isBlank()) coordinateSystem = "GCJ02";
    }

    // ---------------------------------------------------------------
    // 稳定 pathKey 生成
    // ---------------------------------------------------------------

    /**
     * 根据坐标序列生成稳定的 pathKey。
     * 格式：{scope}:{fromAdcode}:{toAdcode}:{hash前16位}
     */
    public static String buildStablePathKey(
            String scope,
            String fromAdcode,
            String toAdcode,
            List<double[]> coordinates
    ) {
        String prefix = (scope != null ? scope : "rm2")
                + ":" + safeAdcode(fromAdcode)
                + ":" + safeAdcode(toAdcode);
        String hash = hashCoordinates(coordinates);
        return prefix + ":" + hash;
    }

    /**
     * 坐标序列 → SHA-256 前 16 位 hex。
     * 坐标保留 5 位小数，相同坐标序列在不同 JVM 实例中产生相同 hash。
     */
    public static String hashCoordinates(List<double[]> coords) {
        if (coords == null || coords.isEmpty()) return "0000000000000000";
        StringBuilder sb = new StringBuilder();
        for (double[] c : coords) {
            if (c == null || c.length < 2) continue;
            sb.append(String.format("%.5f,%.5f;", c[0], c[1]));
        }
        if (sb.isEmpty()) return "0000000000000000";
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(sb.toString().hashCode());
        }
    }

    private static String safeAdcode(String adcode) {
        return adcode != null && !adcode.isBlank() ? adcode.trim() : "000000";
    }
}
