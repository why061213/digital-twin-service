package com.jushen.digitaltwin.townroad;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class VehicleOrderChainStoreTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void deduplicatesLatestAndBuildsChronologicalVehicleLifecycle() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        VehicleOrderChainStore store = store(objectMapper, temporaryDirectory);

        VehicleOrderChainStore.IngestResult first = store.ingest(List.of(
                record("order-1", "route-1", "粤A12345", "待装载", "2026-07-22T08:00:00Z"),
                record("order-1", "route-1", "粤A12345", "运行中", "2026-07-22T09:00:00Z"),
                record("order-1", "route-1", "粤A12345", "待装载", "2026-07-22T07:00:00Z")
        ));
        store.ingest(List.of(record(
                "order-1", "route-1", "粤A12345", "已完成", "2026-07-22T10:00:00Z")));

        assertThat(first.deduplicatedCount()).isEqualTo(1);
        assertThat(first.duplicateCount()).isEqualTo(2);
        Path general = temporaryDirectory.resolve("records/order-route-vehicle.json");
        Path vehicle = temporaryDirectory.resolve("vehicles/粤/A/12345.json");
        assertThat(general).isRegularFile();
        assertThat(vehicle).isRegularFile();

        VehicleOrderChainStore.VehicleFile vehicleFile = objectMapper.readValue(
                vehicle.toFile(), VehicleOrderChainStore.VehicleFile.class);
        assertThat(vehicleFile.orders()).hasSize(1);
        VehicleOrderChainStore.VehicleOrderLifecycle lifecycle = vehicleFile.orders().get(0);
        // 同一批同键只保留 updatedAt 最新记录，因此第一批“运行中”胜出；缺少待装载，链路必须保持不完整。
        assertThat(lifecycle.lifecycleStatus()).isEqualTo("INCOMPLETE");
        assertThat(lifecycle.events()).extracting(VehicleOrderChainStore.LifecycleEvent::stage)
                .containsExactly("RUNNING", "COMPLETED");
    }

    @Test
    void completesLifecycleAcrossSuccessiveSnapshotsAndMatchesVehicleOrders() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        VehicleOrderChainStore store = store(objectMapper, temporaryDirectory);

        store.ingest(List.of(record("order-1", "route-1", "粤A12345", "待装载", "2026-07-22T08:00:00Z")));
        store.ingest(List.of(record("order-1", "route-1", "粤A12345", "运行中", "2026-07-22T09:00:00Z")));
        VehicleOrderChainStore.IngestResult completed = store.ingest(List.of(
                record("order-1", "route-1", "粤A12345", "已完成", "2026-07-22T10:00:00Z")));
        VehicleOrderChainStore.IngestResult matched = store.ingest(List.of(
                record("order-2", "route-2", "粤A12345", "待装载", "2026-07-22T11:00:00Z")));

        assertThat(completed.completedCount()).isEqualTo(1);
        assertThat(matched.otherCount()).isEqualTo(1);
        // 已完成订单和新订单不在同一批，也必须通过历史总库按车辆匹配。
        assertThat(matched.matchedVehicleCount()).isEqualTo(1);
        VehicleOrderChainStore.VehicleFile vehicleFile = objectMapper.readValue(
                temporaryDirectory.resolve("vehicles/粤/A/12345.json").toFile(),
                VehicleOrderChainStore.VehicleFile.class);
        assertThat(vehicleFile.orders()).extracting(VehicleOrderChainStore.VehicleOrderLifecycle::orderId)
                .containsExactly("order-1", "order-2");
        assertThat(vehicleFile.orders().get(0).lifecycleStatus()).isEqualTo("COMPLETE");
        assertThat(vehicleFile.orders().get(0).waitingAtMs())
                .isLessThan(vehicleFile.orders().get(0).runningAtMs());
        assertThat(vehicleFile.orders().get(0).runningAtMs())
                .isLessThan(vehicleFile.orders().get(0).completedAtMs());

        VehicleOrderChainStore.GeneralFile generalFile = objectMapper.readValue(
                temporaryDirectory.resolve("records/order-route-vehicle.json").toFile(),
                VehicleOrderChainStore.GeneralFile.class);
        assertThat(generalFile.completedOrderKeys()).hasSize(1);
        assertThat(generalFile.otherOrderKeys()).hasSize(1);
        assertThat(generalFile.matchedVehicleKeys()).containsExactly("粤A12345");
    }

    @Test
    void usesFirstObservedTimeForWaitingOrderWithoutUpstreamTime() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        VehicleOrderChainStore store = store(objectMapper, temporaryDirectory);

        store.ingest(List.of(record("order-1", "route-1", "粤A12345", "待装载", null)));
        VehicleOrderChainStore.VehicleFile firstFile = objectMapper.readValue(
                temporaryDirectory.resolve("vehicles/粤/A/12345.json").toFile(),
                VehicleOrderChainStore.VehicleFile.class);
        VehicleOrderChainStore.VehicleOrderLifecycle firstLifecycle = firstFile.orders().get(0);
        Long firstObservedTime = firstLifecycle.waitingAtMs();

        store.ingest(List.of(record("order-1", "route-1", "粤A12345", "待装载", null)));
        VehicleOrderChainStore.VehicleFile secondFile = objectMapper.readValue(
                temporaryDirectory.resolve("vehicles/粤/A/12345.json").toFile(),
                VehicleOrderChainStore.VehicleFile.class);
        VehicleOrderChainStore.VehicleOrderLifecycle secondLifecycle = secondFile.orders().get(0);

        assertThat(firstObservedTime).isNotNull();
        assertThat(secondLifecycle.waitingAtMs()).isEqualTo(firstObservedTime);
        assertThat(secondLifecycle.waitingTimeSource()).isEqualTo("FIRST_OBSERVED_AT");
        assertThat(secondLifecycle.events()).hasSize(1);
        VehicleOrderChainStore.LifecycleEvent event = secondLifecycle.events().get(0);
        assertThat(event.eventTimeMs()).isNull();
        assertThat(event.effectiveEventTimeMs()).isEqualTo(firstObservedTime);
        assertThat(event.semanticState())
                .isEqualTo("PICKUP_OR_EN_ROUTE_TO_PICKUP_UNDETERMINED");
    }

    @Test
    void doesNotMarkOutOfOrderStagesAsComplete() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        VehicleOrderChainStore store = store(objectMapper, temporaryDirectory);
        store.ingest(List.of(record("order-1", "route-1", "粤A12345", "待装载", "2026-07-22T10:00:00Z")));
        store.ingest(List.of(record("order-1", "route-1", "粤A12345", "运行中", "2026-07-22T09:00:00Z")));
        store.ingest(List.of(record("order-1", "route-1", "粤A12345", "已完成", "2026-07-22T11:00:00Z")));

        String json = Files.readString(temporaryDirectory.resolve("vehicles/粤/A/12345.json"));
        assertThat(json).contains("\"lifecycleStatus\" : \"INCOMPLETE\"");
    }

    private VehicleOrderChainStore store(ObjectMapper objectMapper, Path root) {
        TownRoadExternalOrderProperties properties = new TownRoadExternalOrderProperties();
        properties.setVehicleOrderChainStorePath(root.toString());
        return new VehicleOrderChainStore(objectMapper, properties);
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
