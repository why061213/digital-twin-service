package com.jushen.digitaltwin.grouping;

/**
 * 可选扩展接口：提供路线起终点所属省份，供稳定的省级展示分页使用。
 */
public interface ProvinceAwareRouteInfo extends RouteInfo {
    String getStartProvince();

    String getEndProvince();
}
