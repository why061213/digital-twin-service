package com.jushen.digitaltwin.web.dto;

import com.fasterxml.jackson.annotation.JsonAlias;

import java.util.List;
import java.util.Map;

public record WarehouseChartManagementRequest(
        @JsonAlias({"city", "目标城市"}) String cityName,
        @JsonAlias({"action", "操作"}) String operation,
        @JsonAlias({"items", "图表"}) List<ChartItem> charts
) {
    public record ChartItem(
            @JsonAlias("位置") Integer position,
            @JsonAlias({"iconType", "图标类型", "图表类型"}) String chartType,
            @JsonAlias({"iconData", "图标数据", "图表数据"}) Map<String, Object> chartData
    ) {}
}
