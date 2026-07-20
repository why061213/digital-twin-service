package com.jushen.digitaltwin.townroad;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DailyOrderStatisticsServiceTest {

    @TempDir
    Path tempDirectory;

    @Test
    void deduplicatesVehiclesAndCountsEachArrivedVehicle() {
        DailyOrderStatisticsService service = new DailyOrderStatisticsService();
        NormalizedTownRoadOrder completedKgVehicle = order("order-1", "line-1", "vehicle-1", 1_000d, "kg", "已完成");
        NormalizedTownRoadOrder runningTonVehicle = order("order-1", "line-1", "vehicle-2", 2d, "吨", "运输中");

        service.applySnapshot(List.of(completedKgVehicle, runningTonVehicle, completedKgVehicle));
        DailyOrderStatisticsService.DailyOrderStatistics first = service.snapshot();

        assertEquals(3d, first.deliveryTotalTons());
        assertEquals(2, first.dispatchedVehicleCount());
        assertEquals(1, first.totalOrderCount());
        assertEquals(1, first.arrivedVehicleCount());

        service.applySnapshot(List.of(order("order-1", "line-1", "vehicle-2", 2d, "吨", "finished")));
        DailyOrderStatisticsService.DailyOrderStatistics completed = service.snapshot();

        assertEquals(3d, completed.deliveryTotalTons());
        assertEquals(2, completed.dispatchedVehicleCount());
        assertEquals(1, completed.totalOrderCount());
        assertEquals(2, completed.arrivedVehicleCount());
    }

    @Test
    void keepsArrivalStickyAndDoesNotCountCancelledVehicles() {
        DailyOrderStatisticsService service = new DailyOrderStatisticsService();
        service.applySnapshot(List.of(order("order-1", "line-1", "vehicle-1", 3d, "吨", "已完成")));
        service.applySnapshot(List.of(order("order-1", "line-1", "vehicle-1", 3d, "吨", "运输中")));
        service.applySnapshot(List.of(order("order-2", "line-2", "vehicle-2", 4d, "吨", "已取消")));

        DailyOrderStatisticsService.DailyOrderStatistics statistics = service.snapshot();
        assertEquals(3d, statistics.deliveryTotalTons());
        assertEquals(1, statistics.dispatchedVehicleCount());
        assertEquals(1, statistics.totalOrderCount());
        assertEquals(1, statistics.arrivedVehicleCount());
    }

    @Test
    void restoresCurrentDayWhenProviderNoLongerReturnsOrders() {
        Path cachePath = tempDirectory.resolve("daily-statistics.json");
        DailyOrderStatisticsService firstProcess = persistentService(cachePath);
        firstProcess.applySnapshot(List.of(
                order("order-1", "line-1", "vehicle-1", 1_000d, "kg", "已完成"),
                order("order-1", "line-1", "vehicle-2", 2d, "吨", "运输中")
        ));

        assertTrue(Files.isRegularFile(cachePath));

        DailyOrderStatisticsService restartedProcess = persistentService(cachePath);
        restartedProcess.applySnapshot(List.of());
        DailyOrderStatisticsService.DailyOrderStatistics restored = restartedProcess.snapshot();

        assertEquals(3d, restored.deliveryTotalTons());
        assertEquals(2, restored.dispatchedVehicleCount());
        assertEquals(1, restored.totalOrderCount());
        assertEquals(1, restored.arrivedVehicleCount());
    }

    private DailyOrderStatisticsService persistentService(Path cachePath) {
        return new DailyOrderStatisticsService(new ObjectMapper(), true, cachePath.toString(), 3);
    }

    private NormalizedTownRoadOrder order(
            String orderId,
            String lineId,
            String vehicleId,
            double cargoWeight,
            String cargoUnit,
            String status
    ) {
        ExternalOrderRecord.Location from = new ExternalOrderRecord.Location(
                "起点", "广东省", "佛山市", "南海区", "440605", new double[]{113.1, 23.0}
        );
        ExternalOrderRecord.Location to = new ExternalOrderRecord.Location(
                "终点", "广东省", "广州市", "番禺区", "440113", new double[]{113.3, 22.9}
        );
        ExternalOrderRecord.Vehicle vehicle = new ExternalOrderRecord.Vehicle(
                "粤A12345", vehicleId, "铝材", cargoWeight, cargoUnit, null, 50d
        );
        String instanceId = orderId + "::" + lineId + "::" + vehicleId;
        return new NormalizedTownRoadOrder(
                orderId, lineId, instanceId, vehicleId,
                "440605", "440113", "440605->440113",
                "440000", "440000",
                List.of(List.of("440000")), List.of("440000"), List.of(1),
                List.of("440600", "440100"), List.of("佛山市", "广州市"),
                List.of(from.coords(), to.coords()), 30d, 50d,
                "group-1", "广东短途运输", from, to, vehicle,
                status, "2026-07-17 14:00:00", "已取消".equals(status), true,
                status, "route-signature"
        );
    }
}
