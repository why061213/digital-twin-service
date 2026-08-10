package com.jushen.digitaltwin.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
@Data
@ConfigurationProperties(prefix = "warehouses")
public class WarehouseProperties {
    private List<WarehouseConfig> warehouses = new ArrayList<>();
    private FocusPanelsConfig focusPanels = new FocusPanelsConfig();
    private ExternalSyncConfig externalSync = new ExternalSyncConfig();

    @Data
    public static class WarehouseConfig {
        private String city;
        private String label;
    }

    @Data
    public static class FocusPanelsConfig {
        /** 聚焦城市时最多展示多少个面板。 */
        private int count = 3;
        /** 所有聚焦面板共用的基础样式。 */
        private PanelStyle style = new PanelStyle();
        /** 面板结构定义，决定表格/图表类型和数据校验规则。 */
        private List<PanelConfig> panels = new ArrayList<>();
    }

    @Data
    public static class PanelStyle {
        private int width = 240;
        private int maxHeight = 360;
        private int padding = 12;
        private int titleFontSize = 13;
        private int bodyFontSize = 11;
        private int chartTextFontSize = 10;
        private String placement = "right";
        private String theme = "cyan-dark";
    }

    @Data
    public static class PanelConfig {
        private String id;
        private String title;
        /** table、bar、line、pie、ring。 */
        private String chartType = "table";
        private int height = 120;
        /** 上传或外部推送数据时必须包含的字段。 */
        private List<String> requiredColumns = new ArrayList<>();
        /** 表格展示列。为空时使用 requiredColumns。 */
        private List<ColumnConfig> columns = new ArrayList<>();
    }

    @Data
    public static class ColumnConfig {
        private String key;
        private String label;
    }

    @Data
    public static class ExternalSyncConfig {
        /** 留空表示对应的 GET 主动同步接口暂未接入上游。 */
        private String citiesUrl = "";
        private String chartsUrl = "";
        private String dataUrl = "";
    }
}
