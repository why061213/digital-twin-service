package com.jushen.digitaltwin.web;

import com.jushen.digitaltwin.service.WarehousePushService;
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
}
