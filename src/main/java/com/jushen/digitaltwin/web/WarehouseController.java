package com.jushen.digitaltwin.web;

import com.jushen.digitaltwin.service.WarehousePushService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
}
