package com.jushen.digitaltwin.townroad;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class VehicleOrderChainStoreTest {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    @TempDir
    Path temporaryDirectory;

    @Test
    void storesDailyLatestRecordAndUpdatesOnlyWhenSnapshotDiffers() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        VehicleOrderChainStore store = store(objectMapper, temporaryDirectory, "2026-07-22T08:00:00Z");

        VehicleOrderChainStore.IngestResult first = store.ingest(List.of(
                record("order-1", "route-1", "粤A12345", "待装载", "2026-07-22T07:00:00Z"),
                record("order-1", "route-1", "粤A12345", "运行中", "2026-07-22T09:00:00Z"),
                record("order-1", "route-1", "粤A12345", "待装载", "2026-07-22T06:00:00Z")
        ));

        assertThat(first.deduplicatedCount()).isEqualTo(1);
        assertThat(first.duplicateCount()).isEqualTo(2);
        assertThat(first.addedCount()).isEqualTo(1);
        Path dailyFile = temporaryDirectory.resolve("records/2026-07-22.json");
        VehicleOrderChainStore.DailyOrderFile daily = objectMapper.readValue(
                dailyFile.toFile(), VehicleOrderChainStore.DailyOrderFile.class);
        assertThat(daily.date()).isEqualTo("2026-07-22");
        assertThat(daily.orders().values()).singleElement()
                .extracting(order -> order.record().status()).isEqualTo("运行中");

        VehicleOrderChainStore.IngestResult unchanged = store.ingest(List.of(
                record("order-1", "route-1", "粤A12345", "运行中", "2026-07-22T09:00:00Z")));
        assertThat(unchanged.unchangedCount()).isEqualTo(1);
        assertThat(unchanged.updatedCount()).isZero();
        assertThat(unchanged.vehicleOrderAddedCount()).isZero();

        VehicleOrderChainStore.IngestResult updated = store.ingest(List.of(
                record("order-1", "route-1", "粤A12345", "已完成", "2026-07-22T10:00:00Z")));
        assertThat(updated.updatedCount()).isEqualTo(1);
        VehicleOrderChainStore.DailyOrderFile updatedDaily = objectMapper.readValue(
                dailyFile.toFile(), VehicleOrderChainStore.DailyOrderFile.class);
        assertThat(updatedDaily.completedOrderKeys()).hasSize(1);
        assertThat(updatedDaily.orders().values()).singleElement()
                .extracting(order -> order.record().status()).isEqualTo("已完成");
    }

    @Test
    void startupLoadsOnlyTodayAndYesterday() {
        ObjectMapper objectMapper = new ObjectMapper();
        VehicleOrderChainStore oldStore = store(
                objectMapper, temporaryDirectory, "2026-07-20T08:00:00Z");
        oldStore.ingest(List.of(record(
                "order-old", "route-old", "粤A12345", "已完成", "2026-07-20T08:00:00Z")));

        VehicleOrderChainStore yesterdayStore = store(
                objectMapper, temporaryDirectory, "2026-07-21T08:00:00Z");
        yesterdayStore.ingest(List.of(record(
                "order-yesterday", "route-yesterday", "粤A12345", "已完成", "2026-07-21T08:00:00Z")));

        VehicleOrderChainStore todayStore = store(
                objectMapper, temporaryDirectory, "2026-07-22T08:00:00Z");

        assertThat(todayStore.recentOrders()).extracting(ExternalOrderRecord::orderId)
                .containsExactly("order-yesterday");
    }

    @Test
    void vehicleFileOnlyAppendsMissingOrdersWithFourBusinessFields() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        VehicleOrderChainStore store = store(objectMapper, temporaryDirectory, "2026-07-22T08:00:00Z");

        store.ingest(List.of(record("order-1", "route-1", "粤A12345", "待装载", null)));
        VehicleOrderChainStore.IngestResult statusChanged = store.ingest(List.of(
                record("order-1", "route-1", "粤A12345", "运行中", "2026-07-22T09:00:00Z")));
        assertThat(statusChanged.vehicleOrderAddedCount()).isZero();

        Path vehiclePath = temporaryDirectory.resolve("vehicles/粤/A/12345.json");
        JsonNode firstVehicleFile = objectMapper.readTree(vehiclePath.toFile());
        assertThat(firstVehicleFile.path("orders")).hasSize(1);
        Set<String> fields = new HashSet<>();
        firstVehicleFile.path("orders").get(0).fieldNames().forEachRemaining(fields::add);
        assertThat(fields).containsExactlyInAnyOrder("orderId", "from", "to", "time");
        assertThat(firstVehicleFile.path("orders").get(0).path("time").asText())
                .isEqualTo("2026-07-22T08:00:00Z");

        VehicleOrderChainStore.IngestResult secondOrder = store.ingest(List.of(
                record("order-2", "route-2", "粤A12345", "待装载", "2026-07-22T11:00:00Z")));
        assertThat(secondOrder.vehicleOrderAddedCount()).isEqualTo(1);
        VehicleOrderChainStore.VehicleFile vehicleFile = objectMapper.readValue(
                vehiclePath.toFile(), VehicleOrderChainStore.VehicleFile.class);
        assertThat(vehicleFile.orders()).extracting(VehicleOrderChainStore.VehicleOrderEntry::orderId)
                .containsExactly("order-1", "order-2");
    }

    @Test
    void matchesCompletedAndNewOrdersForSameVehicleAcrossSnapshots() {
        ObjectMapper objectMapper = new ObjectMapper();
        VehicleOrderChainStore store = store(objectMapper, temporaryDirectory, "2026-07-22T08:00:00Z");
        store.ingest(List.of(record(
                "order-1", "route-1", "粤A12345", "已完成", "2026-07-22T08:00:00Z")));

        VehicleOrderChainStore.IngestResult result = store.ingest(List.of(record(
                "order-2", "route-2", "粤A12345", "待装载", "2026-07-22T09:00:00Z")));

        assertThat(result.matchedVehicleCount()).isEqualTo(1);
    }

    private VehicleOrderChainStore store(
            ObjectMapper objectMapper,
            Path root,
            String instant
    ) {
        TownRoadExternalOrderProperties properties = new TownRoadExternalOrderProperties();
        properties.setVehicleOrderChainStorePath(root.toString());
        return new VehicleOrderChainStore(
                objectMapper, properties, Clock.fixed(Instant.parse(instant), ZONE));
    }

    private ExternalOrderRecord record(
            String orderId, String lineId, String plate, String status, String updatedAt
    ) {
        ExternalOrderRecord.Location from = new ExternalOrderRecord.Location(
                "起点", "广东省", "佛山市", "南海区", "440605", new double[]{113.1, 23.1});
        ExternalOrderRecord.Location to = new ExternalOrderRecord.Location(
                "终点", "广东省", "肇庆市", "四会市", "441284", new double[]{112.7, 23.3});
        ExternalOrderRecord.Vehicle vehicle = new ExternalOrderRecord.Vehicle(
                plate, "vehicle-1", "货物", 10d, "吨", null, 30d);
        return new ExternalOrderRecord(
                orderId, lineId, from, to, vehicle, status, updatedAt, false, true);
    }
}
