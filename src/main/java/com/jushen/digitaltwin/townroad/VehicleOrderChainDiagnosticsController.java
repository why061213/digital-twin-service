package com.jushen.digitaltwin.townroad;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 车辆订单链实验的免登录只读观测窗口。 */
@RestController
@RequestMapping("/api/public/vehicle-order-chain")
public class VehicleOrderChainDiagnosticsController {
    private final VehicleOrderChainStore store;
    private final VehicleTripRuntimeService tripRuntimeService;

    public VehicleOrderChainDiagnosticsController(
            VehicleOrderChainStore store,
            VehicleTripRuntimeService tripRuntimeService
    ) {
        this.store = store;
        this.tripRuntimeService = tripRuntimeService;
    }

    @GetMapping("/transit-metrics")
    public VehicleOrderChainStore.TransitMetrics transitMetrics() {
        return store.transitMetrics();
    }

    /** 当前同车多订单任务簇；只读、免登录，便于核对锚点和各订单集合。 */
    @GetMapping("/trips")
    public java.util.List<VehicleTripRuntimeService.VehicleTripRuntime> trips() {
        return tripRuntimeService.currentTrips();
    }
}
