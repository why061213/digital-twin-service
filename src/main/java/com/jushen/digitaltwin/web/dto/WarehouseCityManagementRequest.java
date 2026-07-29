package com.jushen.digitaltwin.web.dto;

import com.fasterxml.jackson.annotation.JsonAlias;

import java.util.List;

public record WarehouseCityManagementRequest(
        @JsonAlias({"action", "操作"}) String operation,
        @JsonAlias({"items", "城市"}) List<CityItem> cities
) {
    public record CityItem(
            String cityId,
            @JsonAlias({"city", "城市名"}) String cityName,
            @JsonAlias({"warehouse", "仓库名"}) String warehouseName
    ) {}
}
