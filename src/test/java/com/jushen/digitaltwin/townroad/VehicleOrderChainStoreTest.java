package com.jushen.digitaltwin.townroad;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

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
    void springCanCreateStoreWithDesignatedProductionConstructor() {
        TownRoadExternalOrderProperties properties = new TownRoadExternalOrderProperties();
        properties.setVehicleOrderChainStorePath(temporaryDirectory.toString());
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(ObjectMapper.class, () -> new ObjectMapper());
            context.registerBean(TownRoadExternalOrderProperties.class, () -> properties);
            context.register(VehicleOrderChainStore.class);
            context.refresh();

            assertThat(context.getBean(VehicleOrderChainStore.class)).isNotNull();
        }
    }

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
    void vehicleFileAppendsDistinctStatusEventsWithFiveBusinessFields() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        VehicleOrderChainStore store = store(objectMapper, temporaryDirectory, "2026-07-22T08:00:00Z");

        store.ingest(List.of(record("order-1", "route-1", "粤A12345", "待装载", null)));
        VehicleOrderChainStore.IngestResult statusChanged = store.ingest(List.of(
                record("order-1", "route-1", "粤A12345", "运行中", "2026-07-22T09:00:00Z")));
        assertThat(statusChanged.vehicleOrderAddedCount()).isEqualTo(1);

        Path vehiclePath = temporaryDirectory.resolve("vehicles/粤/A/12345.json");
        JsonNode firstVehicleFile = objectMapper.readTree(vehiclePath.toFile());
        assertThat(firstVehicleFile.path("orders")).hasSize(2);
        Set<String> fields = new HashSet<>();
        firstVehicleFile.path("orders").get(0).fieldNames().forEachRemaining(fields::add);
        assertThat(fields).containsExactlyInAnyOrder("orderId", "from", "to", "time", "status");
        assertThat(firstVehicleFile.path("orders").get(0).path("time").asText())
                .isEqualTo("2026-07-22T08:00:00Z");
        assertThat(firstVehicleFile.path("orders")).extracting(node -> node.path("status").asText())
                .containsExactly("待装载", "在途-1");

        VehicleOrderChainStore.IngestResult repeatedStatus = store.ingest(List.of(
                record("order-1", "route-1", "粤A12345", "运输中", "2026-07-22T10:00:00Z")));
        assertThat(repeatedStatus.vehicleOrderAddedCount()).isZero();

        VehicleOrderChainStore.IngestResult completed = store.ingest(List.of(
                record("order-1", "route-1", "粤A12345", "已完成", "2026-07-22T10:30:00Z")));
        assertThat(completed.vehicleOrderAddedCount()).isEqualTo(1);

        VehicleOrderChainStore.IngestResult secondOrder = store.ingest(List.of(
                record("order-2", "route-2", "粤A12345", "待装载", "2026-07-22T11:00:00Z")));
        assertThat(secondOrder.vehicleOrderAddedCount()).isEqualTo(1);
        VehicleOrderChainStore.VehicleFile vehicleFile = objectMapper.readValue(
                vehiclePath.toFile(), VehicleOrderChainStore.VehicleFile.class);
        assertThat(vehicleFile.orders()).extracting(VehicleOrderChainStore.VehicleOrderEntry::orderId)
                .containsExactly("order-1", "order-1", "order-1", "order-2");
        assertThat(vehicleFile.orders()).extracting(VehicleOrderChainStore.VehicleOrderEntry::status)
                .containsExactly("待装载", "在途-1", "已完成-1", "待装载");
    }

    @Test
    void inferredCompletionIsPreservedWhenFormalCompletionIsLaterAppended() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        VehicleOrderChainStore store = store(objectMapper, temporaryDirectory, "2026-07-22T08:00:00Z");
        ExternalOrderRecord transporting = record(
                "order-1", "route-1", "粤A12345", "运输中", "2026-07-22T07:30:00Z");

        store.ingest(List.of(transporting));
        assertThat(store.recordInferredCompletion(transporting)).isTrue();
        assertThat(store.recordedCompletionStatus(transporting)).isEqualTo("已完成-2");
        store.ingest(List.of(record(
                "order-1", "route-1", "粤A12345", "已完成", "2026-07-22T10:30:00Z")));
        store.ingest(List.of(record(
                "order-1", "route-1", "粤A12345", "运输中", "2026-07-22T11:30:00Z")));

        assertThat(store.recordedCompletionStatus(transporting)).isEqualTo("已完成-1");
        assertThat(store.recentStoredOrders()).singleElement()
                .extracting(VehicleOrderChainStore.StoredOrder::category).isEqualTo("COMPLETED");
        VehicleOrderChainStore.VehicleFile vehicleFile = objectMapper.readValue(
                temporaryDirectory.resolve("vehicles/粤/A/12345.json").toFile(),
                VehicleOrderChainStore.VehicleFile.class);
        assertThat(vehicleFile.orders()).extracting(VehicleOrderChainStore.VehicleOrderEntry::status)
                .contains("已完成-2", "已完成-1");
    }

    @Test
    void inferredTransitIsPreservedWhenUpstreamLaterConfirmsTransit() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        VehicleOrderChainStore store = store(objectMapper, temporaryDirectory, "2026-07-22T08:00:00Z");
        ExternalOrderRecord waiting = record(
                "order-1", "route-1", "粤A12345", "待装载", "2026-07-22T07:30:00Z");

        store.ingest(List.of(waiting));
        assertThat(store.recordSuspectedInTransit(waiting)).isTrue();
        assertThat(store.recordSuspectedInTransit(waiting)).isFalse();

        VehicleOrderChainStore.IngestResult repeatedExternalWaiting = store.ingest(List.of(waiting));
        assertThat(repeatedExternalWaiting.unchangedCount()).isEqualTo(1);
        assertThat(repeatedExternalWaiting.updatedCount()).isZero();
        assertThat(repeatedExternalWaiting.vehicleOrderAddedCount()).isZero();
        assertThat(store.recordedTransitStatus(waiting)).isEqualTo("在途-2");

        store.ingest(List.of(record(
                "order-1", "route-1", "粤A12345", "运输中", "2026-07-22T09:00:00Z")));

        assertThat(store.recordedTransitStatus(waiting)).isEqualTo("在途-1");

        ExternalOrderRecord secondWaiting = record(
                "order-2", "route-2", "粤A12345", "待装载", "2026-07-22T07:40:00Z");
        store.ingest(List.of(secondWaiting));
        store.recordSuspectedInTransit(secondWaiting);

        VehicleOrderChainStore.TransitMetrics metrics = store.transitMetrics();
        assertThat(metrics.suspectedTransitCount()).isEqualTo(2);
        assertThat(metrics.upstreamConfirmedCount()).isEqualTo(1);
        assertThat(metrics.awaitingUpstreamConfirmationCount()).isEqualTo(1);
        assertThat(metrics.measurableIntervalCount()).isEqualTo(1);
        assertThat(metrics.confirmationInterval().averageMinutes()).isEqualTo(60d);
        assertThat(metrics.details()).filteredOn(detail -> detail.orderId().equals("order-1"))
                .singleElement()
                .satisfies(detail -> {
                    assertThat(detail.plate()).isEqualTo("粤A12345");
                    assertThat(detail.upstreamConfirmed()).isTrue();
                    assertThat(detail.confirmationIntervalSeconds()).isEqualTo(3600L);
                    assertThat(detail.timeOrderValid()).isTrue();
                });

        Path vehiclePath = temporaryDirectory.resolve("vehicles/粤/A/12345.json");
        VehicleOrderChainStore.VehicleFile vehicleFile = objectMapper.readValue(
                vehiclePath.toFile(), VehicleOrderChainStore.VehicleFile.class);
        assertThat(vehicleFile.orders()).extracting(VehicleOrderChainStore.VehicleOrderEntry::status)
                .containsExactly("待装载", "待装载", "在途-2", "在途-2", "在途-1");
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

    @Test
    void latestObservedOrdersContainOnlyRecordsPresentInLatestUpstreamSnapshot() {
        ObjectMapper objectMapper = new ObjectMapper();
        VehicleOrderChainStore store = store(objectMapper, temporaryDirectory, "2026-07-22T08:00:00Z");
        store.ingest(List.of(
                record("order-1", "route-1", "粤A12345", "待装载", "2026-07-22T07:00:00Z"),
                record("order-2", "route-2", "粤A12345", "运输中", "2026-07-22T07:00:00Z")));

        store.ingest(List.of(
                record("order-2", "route-2", "粤A12345", "运输中", "2026-07-22T07:00:00Z")));

        assertThat(store.recentStoredOrders()).hasSize(2);
        assertThat(store.latestObservedStoredOrders()).extracting(VehicleOrderChainStore.StoredOrder::orderId)
                .containsExactly("order-2");
    }

    @Test
    void activeTrackingIndexLoadsOnlyOpenOrdersAndLatestCompletedContext() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        VehicleOrderChainStore store = store(objectMapper, temporaryDirectory, "2026-07-22T12:00:00Z");
        store.ingest(List.of(
                record("completed-1", "route-1", "粤A12345", "已完成", "2026-07-22T08:00:00Z"),
                record("completed-2", "route-2", "粤A12345", "已完成", "2026-07-22T09:00:00Z"),
                record("open", "route-3", "粤A12345", "待装载", "2026-07-22T10:00:00Z")));

        Path indexPath = temporaryDirectory.resolve("tracking-active-index.json");
        VehicleOrderChainStore.TrackingOrderIndex index = objectMapper.readValue(
                indexPath.toFile(), VehicleOrderChainStore.TrackingOrderIndex.class);
        assertThat(index.orders().values()).extracting(VehicleOrderChainStore.StoredOrder::orderId)
                .containsExactlyInAnyOrder("completed-2", "open");

        VehicleOrderChainStore restarted = store(objectMapper, temporaryDirectory, "2026-07-22T13:00:00Z");
        assertThat(restarted.activeTrackingStoredOrders())
                .extracting(VehicleOrderChainStore.StoredOrder::orderId)
                .containsExactly("completed-2", "open");
        // 纯审计日库仍完整保留三条记录，不被活跃索引裁剪。
        VehicleOrderChainStore.DailyOrderFile daily = objectMapper.readValue(
                temporaryDirectory.resolve("records/2026-07-22.json").toFile(),
                VehicleOrderChainStore.DailyOrderFile.class);
        assertThat(daily.orders()).hasSize(3);
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
