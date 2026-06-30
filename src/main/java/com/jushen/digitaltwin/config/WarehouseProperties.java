package com.jushen.digitaltwin.config;

import lombok.Data;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;


@Component
@Data
@ConfigurationProperties(prefix = "warehouses")
public class WarehouseProperties {
    private List<WarehouseConfig> warehouses = new ArrayList<>();

    @Data
    public static class WarehouseConfig {
        private String city;
        private String label;
    }
}