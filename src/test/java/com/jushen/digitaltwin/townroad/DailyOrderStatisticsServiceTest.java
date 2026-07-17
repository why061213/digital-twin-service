package com.jushen.digitaltwin.townroad;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DailyOrderStatisticsServiceTest {

    @Test
    void deduplicatesSnapshotsAndCompletesOrderOnlyAfterAllVehiclesFinish() {
        DailyOrderStatisticsService service = new DailyOrderStatisticsService();
        ExternalOrderRecord completedKgVehicle = record("order-1", "line-1", 1_000d, "kg", "已完成");
        ExternalOrderRecord runningTonVehicle = record("order-1", "line-2", 2d, "吨", "运输中");

        service.accept(List.of(completedKgVehicle, runningTonVehicle, completedKgVehicle));
        DailyOrderStatisticsService.DailyOrderStatistics first = service.snapshot();

        assertEquals(3d, first.deliveryTotalTons());
        assertEquals(2, first.dispatchedVehicleCount());
        assertEquals(1, first.totalOrderCount());
        assertEquals(0, first.completedOrderCount());

        service.accept(List.of(record("order-1", "line-2", 2d, "吨", "finished")));
        DailyOrderStatisticsService.DailyOrderStatistics completed = service.snapshot();

        assertEquals(3d, completed.deliveryTotalTons());
        assertEquals(2, completed.dispatchedVehicleCount());
        assertEquals(1, completed.totalOrderCount());
        assertEquals(1, completed.completedOrderCount());
    }

    private ExternalOrderRecord record(
            String orderId,
            String lineId,
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
                "粤A12345", "vehicle-1", "铝材", cargoWeight, cargoUnit, null, 50d
        );
        return new ExternalOrderRecord(
                orderId, lineId, from, to, vehicle, status, "2026-07-17 14:00:00", false, true
        );
    }
}
