package com.jushen.digitaltwin.web.dto;

import com.fasterxml.jackson.annotation.JsonAlias;

import java.util.Map;

public record WarehouseDataAdjustmentRequest(
        @JsonAlias({"isSidePanel", "是否为两侧面板"}) Boolean sidePanel,
        String cityId,
        @JsonAlias({"city", "城市名"}) String cityName,
        @JsonAlias("位置") Integer position,
        @JsonAlias("图表数据") Map<String, Object> chartData
) {}
