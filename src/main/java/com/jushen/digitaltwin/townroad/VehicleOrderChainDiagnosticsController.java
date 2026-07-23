package com.jushen.digitaltwin.townroad;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 车辆订单链实验的免登录只读观测窗口。 */
@RestController
@RequestMapping("/api/public/vehicle-order-chain")
public class VehicleOrderChainDiagnosticsController {
    private final VehicleOrderChainStore store;

    public VehicleOrderChainDiagnosticsController(VehicleOrderChainStore store) {
        this.store = store;
    }

    @GetMapping("/transit-metrics")
    public VehicleOrderChainStore.TransitMetrics transitMetrics() {
        return store.transitMetrics();
    }
}
