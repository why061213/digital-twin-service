package com.jushen.digitaltwin.grouping;

import java.util.List;

/**
 * 可选扩展接口：当路线能提供路径标识时，用于判断“不同订单但同一路径”的关系。
 *
 * <p>pathKey 通常由后端根据道路坐标、道路段 ID 或外部路径规划结果生成。
 * 如果业务系统能提供更细的道路段信息，可以同时返回 segmentKeys，为后续“局部共路”
 * 和道路压力分析预留空间。</p>
 */
public interface PathAwareRouteInfo extends RouteInfo {
    String getPathKey();

    default List<String> getSegmentKeys() {
        return List.of();
    }
}
