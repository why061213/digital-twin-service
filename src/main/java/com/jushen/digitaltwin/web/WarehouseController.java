package com.jushen.digitaltwin.web;

import com.jushen.digitaltwin.service.WarehousePushService;
import com.jushen.digitaltwin.web.dto.WarehouseChartManagementRequest;
import com.jushen.digitaltwin.web.dto.WarehouseCityManagementRequest;
import com.jushen.digitaltwin.web.dto.WarehouseDataAdjustmentRequest;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/warehouse")
public class WarehouseController {

    private final WarehousePushService warehousePushService;

    public WarehouseController(WarehousePushService warehousePushService) {
        this.warehousePushService = warehousePushService;
    }

    @GetMapping("/snapshot")
    public List<Map<String, Object>> getSnapshot() {
        return warehousePushService.getWarehouseSnapshot();
    }

    @PostMapping("/snapshot/push")
    public List<Map<String, Object>> pushSnapshot() {
        return warehousePushService.pushWarehouseSnapshot();
    }

    @GetMapping("/focus/{cityName}")
    public Map<String, Object> getFocus(@PathVariable String cityName) {
        return warehousePushService.getWarehouseFocus(cityName);
    }

    @PostMapping("/focus/{cityName}/push")
    public Map<String, Object> pushFocus(@PathVariable String cityName) {
        return warehousePushService.pushWarehouseFocus(cityName);
    }

    @PostMapping("/focus/{cityName}/panels")
    public Map<String, Object> pushExternalPanels(
            @PathVariable String cityName,
            @RequestBody List<Map<String, Object>> panels
    ) {
        return warehousePushService.pushExternalFocusPanels(cityName, panels);
    }

    @PostMapping(value = "/focus/{cityName}/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> uploadFocusTable(
            @PathVariable String cityName,
            @RequestParam String panelId,
            @RequestParam MultipartFile file
    ) throws IOException {
        return warehousePushService.uploadFocusTable(cityName, panelId, file);
    }

    /** 我方主动 GET 上游城市配置并应用；上游地址由 warehouse.yml 配置。 */
    @GetMapping("/china-map/cities/sync")
    public Map<String, Object> pullManagedCities() {
        return warehousePushService.pullCities();
    }

    /** 外部系统向我方推送城市/仓库增删改。 */
    @PostMapping("/china-map/cities")
    public Map<String, Object> manageCities(@RequestBody WarehouseCityManagementRequest request) {
        return warehousePushService.applyCityManagement(request);
    }

    /** 我方主动 GET 上游图表结构并应用。 */
    @GetMapping("/china-map/charts/sync")
    public Map<String, Object> pullManagedCharts() {
        return warehousePushService.pullCharts();
    }

    /** 外部系统向我方推送城市九宫格图表结构。 */
    @PostMapping("/china-map/charts")
    public Map<String, Object> manageCharts(@RequestBody List<WarehouseChartManagementRequest> requests) {
        return warehousePushService.applyChartManagement(requests);
    }

    /** 我方主动 GET 上游图表数据并应用。 */
    @GetMapping("/china-map/data/sync")
    public Map<String, Object> pullManagedData() {
        return warehousePushService.pullData();
    }

    /** 外部系统向我方推送侧面板或中心九宫格的实时数据。 */
    @PostMapping("/china-map/data")
    public Map<String, Object> adjustData(@RequestBody List<WarehouseDataAdjustmentRequest> requests) {
        return warehousePushService.applyDataAdjustments(requests);
    }
}
