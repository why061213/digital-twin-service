package com.jushen.digitaltwin.service;

import com.jushen.digitaltwin.config.WarehouseProperties;
import com.jushen.digitaltwin.model.WarehouseData;
import com.jushen.digitaltwin.websocket.RealtimeWebSocketHandler;
import com.jushen.digitaltwin.web.dto.WarehouseChartManagementRequest;
import com.jushen.digitaltwin.web.dto.WarehouseCityManagementRequest;
import com.jushen.digitaltwin.web.dto.WarehouseDataAdjustmentRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Service
public class WarehousePushService {

    private static final Logger log = LoggerFactory.getLogger(WarehousePushService.class);

    private final RealtimeWebSocketHandler webSocketHandler;
    private final WarehouseDataProvider dataProvider;
    private final WarehouseProperties warehouseProperties;
    private final ConfigService configService;
    private final List<WarehouseProperties.WarehouseConfig> warehouseConfigs;
    private final Map<String, TreeMap<Integer, Map<String, Object>>> centerCharts = new ConcurrentHashMap<>();
    private final Map<String, TreeMap<Integer, Map<String, Object>>> sidePanels = new ConcurrentHashMap<>();
    private final Map<String, String> cityNameById = new ConcurrentHashMap<>();
    private final RestClient restClient = RestClient.create();
    private final Random random = new Random();

    public WarehousePushService(
            RealtimeWebSocketHandler webSocketHandler,
            WarehouseDataProvider dataProvider,
            WarehouseProperties warehouseProperties,
            ConfigService configService
    ) {
        this.webSocketHandler = webSocketHandler;
        this.dataProvider = dataProvider;
        this.warehouseProperties = warehouseProperties;
        this.warehouseConfigs = new CopyOnWriteArrayList<>();
        warehouseProperties.getWarehouses().forEach(config -> {
            WarehouseProperties.WarehouseConfig copy = new WarehouseProperties.WarehouseConfig();
            copy.setCity(config.getCity());
            copy.setLabel(config.getLabel());
            this.warehouseConfigs.add(copy);
        });
    }

    public List<Map<String, Object>> getWarehouseSnapshot() {
        Map<String, WarehouseData> configuredData = new LinkedHashMap<>();
        dataProvider.fetchAllWarehouseData().forEach(data -> configuredData.put(data.getCityName(), data));
        List<Map<String, Object>> messages = new ArrayList<>();
        for (WarehouseProperties.WarehouseConfig config : warehouseConfigs) {
            WarehouseData source = configuredData.get(config.getCity());
            Map<String, Object> displayData = new LinkedHashMap<>();
            if (source != null) displayData.putAll(source.getDisplayData());
            displayData.put("label", config.getLabel());
            TreeMap<Integer, Map<String, Object>> charts = centerCharts.get(config.getCity());
            if (charts != null && !charts.isEmpty()) displayData.put("charts", positionedValues(charts));
            messages.add(warehouseMessage(new WarehouseData(config.getCity(), displayData)));
        }
        return messages;
    }

    public synchronized Map<String, Object> applyCityManagement(WarehouseCityManagementRequest request) {
        String operation = normalizeOperation(request == null ? null : request.operation());
        List<WarehouseCityManagementRequest.CityItem> items = request == null ? null : request.cities();
        if (items == null || items.isEmpty()) throw new IllegalArgumentException("cities must not be empty");
        List<String> replacedCities = "REPLACE".equals(operation)
                ? warehouseConfigs.stream().map(WarehouseProperties.WarehouseConfig::getCity).toList()
                : List.of();
        if ("REPLACE".equals(operation)) {
            warehouseConfigs.clear();
            cityNameById.clear();
        }
        int applied = 0;
        for (WarehouseCityManagementRequest.CityItem item : items) {
            String city = requireText(item.cityName(), "cityName");
            if ("DELETE".equals(operation)) {
                warehouseConfigs.removeIf(config -> city.equals(config.getCity()));
                centerCharts.remove(city);
                sidePanels.remove(city);
                cityNameById.entrySet().removeIf(entry -> city.equals(entry.getValue()));
                webSocketHandler.broadcast(Map.of("type", "warehouse_update", "cityName", city, "action", "fall"));
                applied++;
                continue;
            }
            String warehouseName = requireText(item.warehouseName(), "warehouseName");
            warehouseConfigs.removeIf(config -> city.equals(config.getCity()));
            WarehouseProperties.WarehouseConfig config = new WarehouseProperties.WarehouseConfig();
            config.setCity(city);
            config.setLabel(warehouseName);
            warehouseConfigs.add(config);
            if (item.cityId() != null && !item.cityId().isBlank()) {
                cityNameById.put(item.cityId().trim(), city);
            }
            webSocketHandler.broadcast(warehouseMessage(new WarehouseData(city, Map.of("label", warehouseName))));
            applied++;
        }
        if ("REPLACE".equals(operation)) {
            replacedCities.stream()
                    .filter(oldCity -> warehouseConfigs.stream().noneMatch(config -> oldCity.equals(config.getCity())))
                    .forEach(oldCity -> webSocketHandler.broadcast(Map.of(
                            "type", "warehouse_update", "cityName", oldCity, "action", "fall"
                    )));
        }
        return result("cities", operation, applied, getWarehouseSnapshot());
    }

    public synchronized Map<String, Object> applyChartManagement(List<WarehouseChartManagementRequest> requests) {
        if (requests == null || requests.isEmpty()) throw new IllegalArgumentException("request body must not be empty");
        int applied = 0;
        for (WarehouseChartManagementRequest request : requests) {
            String city = requireKnownCity(request.cityName(), null);
            String operation = normalizeOperation(request.operation());
            List<WarehouseChartManagementRequest.ChartItem> charts = request.charts();
            if (charts == null || charts.isEmpty()) throw new IllegalArgumentException("charts must not be empty");
            TreeMap<Integer, Map<String, Object>> slots = centerCharts.computeIfAbsent(city, ignored -> new TreeMap<>());
            if ("REPLACE".equals(operation)) slots.clear();
            int sequentialPosition = 1;
            for (WarehouseChartManagementRequest.ChartItem chart : charts) {
                Integer position = chart.position();
                if ("DELETE".equals(operation)) {
                    validatePosition(position, 8, "chart delete position");
                    slots.remove(position);
                } else {
                    if (position == null) {
                        while (slots.containsKey(sequentialPosition) && sequentialPosition <= 8) sequentialPosition++;
                        position = sequentialPosition;
                    }
                    validatePosition(position, 8, "chart position");
                    String chartType = requireText(chart.chartType(), "chartType");
                    if (chart.chartData() == null) throw new IllegalArgumentException("chartData is required");
                    slots.put(position, normalizeChart(position, chartType, chart.chartData()));
                    sequentialPosition = position + 1;
                }
                applied++;
            }
            broadcastChartUpdate(city, false, slots);
        }
        return result("charts", "BATCH", applied, requests.size());
    }

    public synchronized Map<String, Object> applyDataAdjustments(List<WarehouseDataAdjustmentRequest> requests) {
        if (requests == null || requests.isEmpty()) throw new IllegalArgumentException("request body must not be empty");
        int applied = 0;
        for (WarehouseDataAdjustmentRequest request : requests) {
            if (request.sidePanel() == null) throw new IllegalArgumentException("sidePanel is required");
            String city = requireKnownCity(request.cityName(), request.cityId());
            int maxPosition = request.sidePanel() ? 4 : 8;
            validatePosition(request.position(), maxPosition, "position");
            if (request.chartData() == null) throw new IllegalArgumentException("chartData is required");
            Map<String, TreeMap<Integer, Map<String, Object>>> target = request.sidePanel() ? sidePanels : centerCharts;
            TreeMap<Integer, Map<String, Object>> slots = target.computeIfAbsent(city, ignored -> new TreeMap<>());
            Map<String, Object> merged = new LinkedHashMap<>(slots.getOrDefault(request.position(), Map.of()));
            merged.putAll(request.chartData());
            merged.put("position", request.position());
            slots.put(request.position(), merged);
            if (request.sidePanel()) {
                webSocketHandler.broadcast(warehouseFocusMessage(city, positionedValues(slots)));
            } else {
                broadcastChartUpdate(city, false, slots);
            }
            applied++;
        }
        return result("data", "ADJUST", applied, requests.size());
    }

    public Map<String, Object> pullCities() {
        String url = warehouseProperties.getExternalSync().getCitiesUrl();
        if (url == null || url.isBlank()) return unconfigured("cities");
        WarehouseCityManagementRequest body = restClient.get().uri(url).retrieve().body(WarehouseCityManagementRequest.class);
        return applyCityManagement(body);
    }

    public Map<String, Object> pullCharts() {
        String url = warehouseProperties.getExternalSync().getChartsUrl();
        if (url == null || url.isBlank()) return unconfigured("charts");
        List<WarehouseChartManagementRequest> body = restClient.get().uri(url).retrieve()
                .body(new ParameterizedTypeReference<>() {});
        return applyChartManagement(body);
    }

    public Map<String, Object> pullData() {
        String url = warehouseProperties.getExternalSync().getDataUrl();
        if (url == null || url.isBlank()) return unconfigured("data");
        List<WarehouseDataAdjustmentRequest> body = restClient.get().uri(url).retrieve()
                .body(new ParameterizedTypeReference<>() {});
        return applyDataAdjustments(body);
    }

    private String normalizeOperation(String operation) {
        String value = requireText(operation, "operation").trim().toUpperCase();
        return switch (value) {
            case "ADD", "添加" -> "ADD";
            case "DELETE", "REMOVE", "删减", "删除" -> "DELETE";
            case "REPLACE", "OVERWRITE", "覆盖" -> "REPLACE";
            default -> throw new IllegalArgumentException("operation must be ADD/DELETE/REPLACE (添加/删减/覆盖)");
        };
    }

    private String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value.trim();
    }

    private String requireKnownCity(String cityName, String cityId) {
        String city = cityName;
        if ((city == null || city.isBlank()) && cityId != null && !cityId.isBlank()) {
            city = cityNameById.get(cityId.trim());
        }
        String resolvedCity = requireText(city, "cityName or known cityId");
        boolean known = warehouseConfigs.stream().anyMatch(config -> resolvedCity.equals(config.getCity()));
        if (!known) throw new IllegalArgumentException("Unknown cityName: " + resolvedCity);
        return resolvedCity;
    }

    private void validatePosition(Integer position, int max, String field) {
        if (position == null || position < 1 || position > max) {
            throw new IllegalArgumentException(field + " must be between 1 and " + max);
        }
    }

    private Map<String, Object> normalizeChart(int position, String chartType, Map<String, Object> chartData) {
        Map<String, Object> chart = new LinkedHashMap<>(chartData);
        chart.put("position", position);
        chart.put("chartType", chartType);
        chart.putIfAbsent("id", "managed-chart-" + position);
        chart.putIfAbsent("title", "图表 " + position);
        return chart;
    }

    private List<Map<String, Object>> positionedValues(TreeMap<Integer, Map<String, Object>> slots) {
        return slots.entrySet().stream().map(entry -> {
            Map<String, Object> value = new LinkedHashMap<>(entry.getValue());
            value.put("position", entry.getKey());
            return value;
        }).toList();
    }

    private void broadcastChartUpdate(String city, boolean sidePanel, TreeMap<Integer, Map<String, Object>> slots) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("type", "warehouse_chart_update");
        message.put("cityName", city);
        message.put("sidePanel", sidePanel);
        message.put("charts", positionedValues(slots));
        webSocketHandler.broadcast(message);
    }

    private Map<String, Object> result(String resource, String operation, int applied, Object data) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("resource", resource);
        response.put("operation", operation);
        response.put("applied", applied);
        response.put("data", data);
        return response;
    }

    private Map<String, Object> unconfigured(String resource) {
        return Map.of(
                "resource", resource,
                "configured", false,
                "applied", 0,
                "message", "external sync URL is not configured"
        );
    }

    public List<Map<String, Object>> pushWarehouseSnapshot() {
        List<Map<String, Object>> messages = getWarehouseSnapshot();
        messages.forEach(webSocketHandler::broadcast);
        return messages;
    }

    public Map<String, Object> getWarehouseFocus(String cityName) {
        return warehouseFocusMessage(cityName, createFocusPanels(cityName));
    }

    public Map<String, Object> pushWarehouseFocus(String cityName) {
        Map<String, Object> message = getWarehouseFocus(cityName);
        webSocketHandler.broadcast(message);
        return message;
    }

    /**
     * 外部系统推送聚焦面板数据。数据会先按 warehouse.yml 中的结构定义校验，
     * 通过后再统一转成 warehouse_focus 消息推给前端。
     */
    public Map<String, Object> pushExternalFocusPanels(String cityName, List<Map<String, Object>> panels) {
        List<Map<String, Object>> normalizedPanels = validateAndNormalizePanels(panels);
        Map<String, Object> message = warehouseFocusMessage(cityName, normalizedPanels);
        webSocketHandler.broadcast(message);
        return message;
    }

    /**
     * 上传 CSV 表格，并按指定面板的 requiredColumns 校验表头。
     * 这里先用 CSV 是为了避免额外 Excel 依赖；后续接 xlsx 时可在这里替换解析器。
     */
    public Map<String, Object> uploadFocusTable(String cityName, String panelId, MultipartFile file) throws IOException {
        WarehouseProperties.PanelConfig config = findPanel(panelId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown panelId: " + panelId));
        List<Map<String, Object>> rows = parseCsv(file);
        validateRows(config, rows);

        Map<String, Object> panel = panelFromConfig(config, rows);
        Map<String, Object> message = warehouseFocusMessage(cityName, List.of(panel));
        webSocketHandler.broadcast(message);
        log.info("Broadcasting warehouse_focus for {}", cityName);
        return message;
    }

    private Map<String, Object> warehouseMessage(WarehouseData data) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("type", "warehouse_update");
        message.put("cityName", data.getCityName());
        message.put("action", "rise");
        message.put("displayData", data.getDisplayData());
        return message;
    }

    private Map<String, Object> warehouseFocusMessage(String cityName, List<Map<String, Object>> panels) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("type", "warehouse_focus");
        message.put("cityName", cityName);
        message.put("style", focusPanelStyle());
        message.put("panels", panels);
        return message;
    }

    private Map<String, Object> focusPanelStyle() {
        WarehouseProperties.PanelStyle style = warehouseProperties.getFocusPanels().getStyle();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("width", Math.max(1, configService.getConfig().getWarehousePanelWidth()));
        result.put("maxHeight", Math.max(1, configService.getConfig().getWarehousePanelMaxHeight()));
        result.put("padding", style.getPadding());
        result.put("titleFontSize", style.getTitleFontSize());
        result.put("bodyFontSize", style.getBodyFontSize());
        result.put("chartTextFontSize", style.getChartTextFontSize());
        result.put("placement", style.getPlacement());
        result.put("theme", style.getTheme());
        return result;
    }

    private List<Map<String, Object>> createFocusPanels(String cityName) {
        TreeMap<Integer, Map<String, Object>> managedPanels = sidePanels.get(cityName);
        if (managedPanels != null && !managedPanels.isEmpty()) return positionedValues(managedPanels);
        int limit = Math.max(0, warehouseProperties.getFocusPanels().getCount());
        List<Map<String, Object>> panels = new ArrayList<>();
        for (WarehouseProperties.PanelConfig config : warehouseProperties.getFocusPanels().getPanels()) {
            if (panels.size() >= limit) break;
            List<Map<String, Object>> rows = mockRows(config, cityName);
            panels.add(panelFromConfig(config, rows));
        }
        return panels;
    }

    private Map<String, Object> panelFromConfig(WarehouseProperties.PanelConfig config, List<Map<String, Object>> rows) {
        Map<String, Object> panel = new LinkedHashMap<>();
        panel.put("id", config.getId());
        panel.put("title", config.getTitle());
        panel.put("chartType", config.getChartType());
        panel.put("height", config.getHeight());
        panel.put("columns", config.getColumns());
        panel.put("rows", rows);
        panel.put("option", chartOption(config, rows));
        return panel;
    }

    private List<Map<String, Object>> validateAndNormalizePanels(List<Map<String, Object>> panels) {
        List<Map<String, Object>> normalized = new ArrayList<>();
        for (Map<String, Object> panel : panels) {
            String panelId = String.valueOf(panel.get("id"));
            WarehouseProperties.PanelConfig config = findPanel(panelId)
                    .orElseThrow(() -> new IllegalArgumentException("Unknown panelId: " + panelId));
            Object rowsValue = panel.get("rows");
            if (!(rowsValue instanceof List<?> rowList)) {
                throw new IllegalArgumentException("Panel " + panelId + " must contain rows");
            }
            List<Map<String, Object>> rows = new ArrayList<>();
            for (Object item : rowList) {
                if (!(item instanceof Map<?, ?> rawRow)) {
                    throw new IllegalArgumentException("Panel " + panelId + " row must be object");
                }
                Map<String, Object> row = new LinkedHashMap<>();
                rawRow.forEach((key, value) -> row.put(String.valueOf(key), value));
                rows.add(row);
            }
            validateRows(config, rows);
            normalized.add(panelFromConfig(config, rows));
        }
        return normalized;
    }

    private void validateRows(WarehouseProperties.PanelConfig config, List<Map<String, Object>> rows) {
        for (Map<String, Object> row : rows) {
            for (String column : config.getRequiredColumns()) {
                if (!row.containsKey(column)) {
                    throw new IllegalArgumentException("Panel " + config.getId() + " missing required column: " + column);
                }
            }
        }
    }

    private Optional<WarehouseProperties.PanelConfig> findPanel(String panelId) {
        return warehouseProperties.getFocusPanels().getPanels().stream()
                .filter(panel -> panel.getId().equals(panelId))
                .findFirst();
    }

    private List<Map<String, Object>> mockRows(WarehouseProperties.PanelConfig config, String cityName) {
        return switch (config.getId()) {
            case "inventory-table" -> inventoryRows(cityName);
            case "throughput-bar" -> throughputRows(cityName);
            case "category-ring" -> categoryRows(cityName);
            case "stock-line" -> stockTrendRows(cityName);
            case "capacity-pie" -> capacityRows(cityName);
            case "inbound-table" -> inboundTaskRows(cityName);
            case "outbound-bar" -> outboundRows(cityName);
            case "warning-table" -> statusRows(cityName);
            default -> switch (config.getChartType()) {
                case "bar", "line" -> throughputRows(cityName);
                case "pie", "ring" -> categoryRows(cityName);
                default -> inventoryRows(cityName);
            };
        };
    }

    private int citySeed(String cityName) {
        return Math.abs(cityName.hashCode());
    }

    private List<Map<String, Object>> inventoryRows(String cityName) {
        int seed = citySeed(cityName);
        int total = 3600 + seed % 2600 + random.nextInt(260);
        int todayIn = 160 + seed % 90 + random.nextInt(40);
        int todayOut = 140 + seed % 80 + random.nextInt(36);
        return List.of(
                row("metric", "总库存", "value", total, "unit", "吨"),
                row("metric", "今日入库", "value", todayIn, "unit", "吨"),
                row("metric", "今日出库", "value", todayOut, "unit", "吨")
        );
    }

    private List<Map<String, Object>> throughputRows(String cityName) {
        int seed = citySeed(cityName) % 60;
        List<Map<String, Object>> rows = new ArrayList<>();
        int[] base = {90, 150, 220, 260, 190};
        int index = 0;
        for (String name : List.of("08:00", "10:00", "12:00", "14:00", "16:00")) {
            rows.add(row("name", name, "value", base[index++] + seed + random.nextInt(28)));
        }
        return rows;
    }

    private List<Map<String, Object>> categoryRows(String cityName) {
        int seed = citySeed(cityName) % 120;
        return List.of(
                row("name", "铝锭", "value", 720 + seed + random.nextInt(80)),
                row("name", "铜材", "value", 560 + seed / 2 + random.nextInt(70)),
                row("name", "钢材", "value", 840 + seed + random.nextInt(90)),
                row("name", "化工", "value", 360 + seed / 3 + random.nextInt(50)),
                row("name", "其他", "value", 220 + random.nextInt(40))
        );
    }

    private List<Map<String, Object>> stockTrendRows(String cityName) {
        int seed = citySeed(cityName) % 400;
        int start = 4200 + seed;
        int[] delta = {0, 80, 150, 90, 180};
        List<Map<String, Object>> rows = new ArrayList<>();
        int index = 0;
        for (String name : List.of("08:00", "10:00", "12:00", "14:00", "16:00")) {
            rows.add(row("name", name, "value", start + delta[index++] + random.nextInt(36)));
        }
        return rows;
    }

    private List<Map<String, Object>> capacityRows(String cityName) {
        int seed = citySeed(cityName) % 100;
        return List.of(
                row("name", "已用", "value", 64 + seed % 10),
                row("name", "空余", "value", 22 + seed % 6),
                row("name", "预留", "value", 10 + seed % 5)
        );
    }

    private List<Map<String, Object>> inboundTaskRows(String cityName) {
        int seed = citySeed(cityName);
        return List.of(
                row("metric", "待入库", "value", 8 + seed % 7, "unit", "车"),
                row("metric", "卸货中", "value", 3 + seed % 4, "unit", "车"),
                row("metric", "平均等待", "value", 12 + seed % 8, "unit", "分钟")
        );
    }

    private List<Map<String, Object>> outboundRows(String cityName) {
        int seed = citySeed(cityName) % 40;
        int[] base = {70, 120, 175, 210, 160};
        List<Map<String, Object>> rows = new ArrayList<>();
        int index = 0;
        for (String name : List.of("08:00", "10:00", "12:00", "14:00", "16:00")) {
            rows.add(row("name", name, "value", base[index++] + seed + random.nextInt(22)));
        }
        return rows;
    }

    private List<Map<String, Object>> statusRows(String cityName) {
        int seed = citySeed(cityName);
        return List.of(
                row("metric", "设备在线", "value", 96 + seed % 4, "unit", "%"),
                row("metric", "当前告警", "value", seed % 3, "unit", "条"),
                row("metric", "库内温度", "value", 22 + seed % 5, "unit", "℃"),
                row("metric", "库内湿度", "value", 48 + seed % 8, "unit", "%")
        );
    }

    private Map<String, Object> chartOption(WarehouseProperties.PanelConfig config, List<Map<String, Object>> rows) {
        String type = config.getChartType();
        if ("bar".equals(type) || "line".equals(type)) {
            Map<String, Object> series = new LinkedHashMap<>();
            series.put("type", type);
            series.put("smooth", true);
            series.put("data", rows.stream().map(row -> row.get("value")).toList());
            if ("bar".equals(type)) {
                series.put("barWidth", "42%");
            } else {
                series.put("symbolSize", 6);
            }
            return Map.of(
                    "grid", Map.of("left", 34, "right", 12, "top", 18, "bottom", 24),
                    "xAxis", Map.of("type", "category", "data", rows.stream().map(row -> row.get("name")).toList()),
                    "yAxis", Map.of("type", "value", "scale", "line".equals(type)),
                    "series", List.of(series)
            );
        }
        if ("pie".equals(type) || "ring".equals(type)) {
            return Map.of(
                    "legend", Map.of(
                            "show", true,
                            "bottom", 0,
                            "left", "center",
                            "textStyle", Map.of("color", "#cbd5e1", "fontSize", 10)
                    ),
                    "series", List.of(Map.of(
                            "type", "pie",
                            "radius", "ring".equals(type) ? List.of("46%", "66%") : "58%",
                            "center", List.of("50%", "44%"),
                            "avoidLabelOverlap", true,
                            "label", Map.of("show", false),
                            "labelLine", Map.of("show", false),
                            "data", rows
                    ))
            );
        }
        return Map.of();
    }

    private List<Map<String, Object>> parseCsv(MultipartFile file) throws IOException {
        String content = new String(file.getBytes(), StandardCharsets.UTF_8).replace("\uFEFF", "");
        String[] lines = content.split("\\R");
        if (lines.length < 2) {
            throw new IllegalArgumentException("CSV must contain header and at least one data row");
        }
        String[] headers = splitCsvLine(lines[0]);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 1; i < lines.length; i++) {
            if (lines[i].isBlank()) continue;
            String[] values = splitCsvLine(lines[i]);
            Map<String, Object> row = new LinkedHashMap<>();
            for (int j = 0; j < headers.length; j++) {
                row.put(headers[j].trim(), j < values.length ? values[j].trim() : "");
            }
            rows.add(row);
        }
        return rows;
    }

    private String[] splitCsvLine(String line) {
        return line.split("\\s*,\\s*");
    }

    private Map<String, Object> row(Object... values) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i + 1 < values.length; i += 2) {
            row.put(String.valueOf(values[i]), values[i + 1]);
        }
        return row;
    }
}
