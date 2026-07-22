package com.jushen.digitaltwin.townroad;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 实验中间层的本地订单库。
 *
 * <p>第一份库按订单+路线+车辆保存最新记录与所有不同历史观测；第二份库按车牌目录
 * 保存车辆参与的全部订单，并严格检查“待装载 → 运行中 → 已完成”的时间顺序。</p>
 */
@Service
public class VehicleOrderChainStore {
    private static final Logger log = LoggerFactory.getLogger(VehicleOrderChainStore.class);
    private static final int SCHEMA_VERSION = 1;

    private final ObjectMapper objectMapper;
    private final Path root;
    private final Path generalDatabasePath;
    private final Map<String, StoredOrder> ordersByKey = new LinkedHashMap<>();
    private boolean loaded;

    public VehicleOrderChainStore(ObjectMapper objectMapper, TownRoadExternalOrderProperties properties) {
        this.objectMapper = objectMapper;
        String configuredPath = properties.getVehicleOrderChainStorePath();
        this.root = Path.of(configuredPath == null || configuredPath.isBlank()
                ? "runtime-data/vehicle-order-chain" : configuredPath).toAbsolutePath().normalize();
        this.generalDatabasePath = root.resolve("records").resolve("order-route-vehicle.json");
    }

    public synchronized IngestResult ingest(List<ExternalOrderRecord> records) {
        ensureLoaded();
        long observedAt = System.currentTimeMillis();
        List<ExternalOrderRecord> safeRecords = records == null ? List.of() : records;
        Map<String, ExternalOrderRecord> latestBatchRecords = deduplicateLatest(safeRecords);
        int completedCount = 0;
        int otherCount = 0;
        int addedObservationCount = 0;
        Set<String> affectedPlates = new LinkedHashSet<>();

        for (Map.Entry<String, ExternalOrderRecord> entry : latestBatchRecords.entrySet()) {
            ExternalOrderRecord record = entry.getValue();
            String plate = vehicleKey(record);
            boolean completed = lifecycleStage(record.status()) == LifecycleStage.COMPLETED;
            if (completed) {
                completedCount++;
            } else {
                otherCount++;
            }
            affectedPlates.add(plate);
            if (mergeObservation(entry.getKey(), record, observedAt)) addedObservationCount++;
        }

        Set<String> completedPlates = vehicleKeysByCategory("COMPLETED");
        Set<String> otherPlates = vehicleKeysByCategory("OTHER");
        Set<String> matchedVehicles = new LinkedHashSet<>(completedPlates);
        matchedVehicles.retainAll(otherPlates);
        writeGeneralDatabase(matchedVehicles);
        int completeLifecycleCount = 0;
        for (String plate : affectedPlates) {
            VehicleFile vehicleFile = buildVehicleFile(plate);
            writeJson(vehicleFilePath(plate), vehicleFile);
            completeLifecycleCount += (int) vehicleFile.orders().stream()
                    .filter(order -> "COMPLETE".equals(order.lifecycleStatus())).count();
        }

        IngestResult result = new IngestResult(
                safeRecords.size(), latestBatchRecords.size(),
                safeRecords.size() - latestBatchRecords.size(),
                completedCount, otherCount, matchedVehicles.size(),
                addedObservationCount, affectedPlates.size(), completeLifecycleCount,
                root.toString(), generalDatabasePath.toString());
        log.warn("[VehicleOrderChain][PIPELINE_CUT] stored snapshot and stopped downstream: {}", result);
        return result;
    }

    private Map<String, ExternalOrderRecord> deduplicateLatest(List<ExternalOrderRecord> records) {
        Map<String, ExternalOrderRecord> result = new LinkedHashMap<>();
        for (ExternalOrderRecord record : records) {
            if (record == null) continue;
            String key = recordKey(record);
            ExternalOrderRecord current = result.get(key);
            if (current == null || compareRecordTime(record, current) >= 0) result.put(key, record);
        }
        return result;
    }

    private int compareRecordTime(ExternalOrderRecord left, ExternalOrderRecord right) {
        Long leftTime = parseTime(left.updatedAt());
        Long rightTime = parseTime(right.updatedAt());
        if (leftTime != null && rightTime != null) return Long.compare(leftTime, rightTime);
        if (leftTime != null) return 1;
        if (rightTime != null) return -1;
        return safe(left.updatedAt()).compareTo(safe(right.updatedAt()));
    }

    private boolean mergeObservation(String key, ExternalOrderRecord record, long observedAt) {
        StoredOrder current = ordersByKey.get(key);
        String fingerprint = fingerprint(record);
        if (current != null && current.observations().stream()
                .anyMatch(observation -> fingerprint.equals(observation.fingerprint()))) {
            return false;
        }
        LifecycleStage stage = lifecycleStage(record.status());
        Long upstreamEventTime = parseTime(record.updatedAt());
        Long effectiveEventTime = upstreamEventTime != null
                ? upstreamEventTime
                : stage == LifecycleStage.WAITING ? observedAt : null;
        String timeSource = upstreamEventTime != null
                ? "UPSTREAM_UPDATED_AT"
                : stage == LifecycleStage.WAITING ? "FIRST_OBSERVED_AT" : "UNAVAILABLE";
        String semanticState = stage == LifecycleStage.WAITING
                ? "PICKUP_OR_EN_ROUTE_TO_PICKUP_UNDETERMINED"
                : stage.name();
        StoredObservation observation = new StoredObservation(
                record.status(), stage.name(), semanticState, record.updatedAt(),
                upstreamEventTime, effectiveEventTime, timeSource,
                observedAt, fingerprint, record);
        List<StoredObservation> observations = new ArrayList<>(
                current == null ? List.of() : current.observations());
        observations.add(observation);
        observations.sort(observationComparator());
        ExternalOrderRecord latest = current == null || compareRecordTime(record, current.latest()) >= 0
                ? record : current.latest();
        ordersByKey.put(key, new StoredOrder(
                key, safe(record.orderId()), routeKey(record), vehicleKey(record),
                category(latest), latest, List.copyOf(observations)));
        return true;
    }

    private Comparator<StoredObservation> observationComparator() {
        return Comparator
                .comparing((StoredObservation observation) -> observation.effectiveEventTimeMs() == null)
                .thenComparing(observation -> observation.effectiveEventTimeMs() == null
                        ? observation.observedAtMs() : observation.effectiveEventTimeMs())
                .thenComparingLong(StoredObservation::observedAtMs);
    }

    private VehicleFile buildVehicleFile(String plate) {
        List<VehicleOrderLifecycle> lifecycles = ordersByKey.values().stream()
                .filter(order -> plate.equals(order.vehicleKey()))
                .map(this::toLifecycle)
                .sorted(Comparator
                        .comparing((VehicleOrderLifecycle lifecycle) -> lifecycle.firstEventTimeMs() == null)
                        .thenComparing(lifecycle -> lifecycle.firstEventTimeMs() == null
                                ? Long.MAX_VALUE : lifecycle.firstEventTimeMs())
                        .thenComparing(VehicleOrderLifecycle::orderId)
                        .thenComparing(VehicleOrderLifecycle::routeKey))
                .toList();
        return new VehicleFile(SCHEMA_VERSION, plate, Instant.now().toString(), lifecycles);
    }

    private VehicleOrderLifecycle toLifecycle(StoredOrder order) {
        Long waitingAt = firstStageTime(order.observations(), LifecycleStage.WAITING);
        Long runningAt = firstStageTime(order.observations(), LifecycleStage.RUNNING);
        Long completedAt = firstStageTime(order.observations(), LifecycleStage.COMPLETED);
        boolean complete = waitingAt != null && runningAt != null && completedAt != null
                && waitingAt <= runningAt && runningAt <= completedAt;
        List<LifecycleEvent> events = order.observations().stream()
                .map(observation -> new LifecycleEvent(
                        observation.stage(), observation.semanticState(), observation.status(),
                        observation.updatedAt(), observation.eventTimeMs(),
                        observation.effectiveEventTimeMs(), observation.timeSource(),
                        observation.observedAtMs()))
                .toList();
        Long firstEventTime = events.stream().map(LifecycleEvent::effectiveEventTimeMs)
                .filter(value -> value != null).min(Long::compareTo).orElse(null);
        Long lastEventTime = events.stream().map(LifecycleEvent::effectiveEventTimeMs)
                .filter(value -> value != null).max(Long::compareTo).orElse(null);
        String waitingTimeSource = order.observations().stream()
                .filter(observation -> LifecycleStage.WAITING.name().equals(observation.stage()))
                .filter(observation -> waitingAt != null && waitingAt.equals(observation.effectiveEventTimeMs()))
                .map(StoredObservation::timeSource)
                .findFirst().orElse(null);
        return new VehicleOrderLifecycle(
                order.orderId(), order.routeKey(), complete ? "COMPLETE" : "INCOMPLETE",
                waitingAt, waitingTimeSource, runningAt, completedAt,
                firstEventTime, lastEventTime, events);
    }

    private Long firstStageTime(List<StoredObservation> observations, LifecycleStage stage) {
        return observations.stream()
                .filter(observation -> stage.name().equals(observation.stage()))
                .map(StoredObservation::effectiveEventTimeMs)
                .filter(value -> value != null)
                .min(Long::compareTo)
                .orElse(null);
    }

    private LifecycleStage lifecycleStage(String status) {
        String normalized = safe(status).replace(" ", "");
        if (normalized.contains("已完成") || normalized.equals("完成")) return LifecycleStage.COMPLETED;
        if (normalized.contains("运输中") || normalized.contains("运行中")) return LifecycleStage.RUNNING;
        if (normalized.contains("待装载") || normalized.contains("待装货")) return LifecycleStage.WAITING;
        return LifecycleStage.OTHER;
    }

    private String category(ExternalOrderRecord record) {
        return lifecycleStage(record == null ? null : record.status()) == LifecycleStage.COMPLETED
                ? "COMPLETED" : "OTHER";
    }

    private Set<String> vehicleKeysByCategory(String category) {
        Set<String> vehicleKeys = new LinkedHashSet<>();
        for (StoredOrder order : ordersByKey.values()) {
            if (category.equals(category(order.latest()))) vehicleKeys.add(order.vehicleKey());
        }
        return vehicleKeys;
    }

    private String recordKey(ExternalOrderRecord record) {
        return safe(record.orderId()) + "|" + routeKey(record) + "|" + vehicleKey(record);
    }

    private String routeKey(ExternalOrderRecord record) {
        if (record.lineId() != null && !record.lineId().isBlank()) return record.lineId().trim();
        return locationKey(record.from()) + ">" + locationKey(record.to());
    }

    private String locationKey(ExternalOrderRecord.Location location) {
        if (location == null) return "unknown";
        if (location.adcode() != null && !location.adcode().isBlank()) return location.adcode().trim();
        return safe(location.province()) + "/" + safe(location.city()) + "/"
                + safe(location.district()) + "/" + safe(location.name());
    }

    private String vehicleKey(ExternalOrderRecord record) {
        ExternalOrderRecord.Vehicle vehicle = record.vehicle();
        String candidate = vehicle == null ? "" : firstNonBlank(vehicle.plate(), vehicle.carId());
        String normalized = candidate.trim().toUpperCase(Locale.ROOT)
                .replaceAll("[\\s.·•・—–_-]", "");
        return normalized.isBlank() ? "UNKNOWN" : normalized;
    }

    private Path vehicleFilePath(String plate) {
        String normalized = plate == null || plate.isBlank() ? "UNKNOWN" : plate;
        String province = firstCodePoint(normalized, "未知");
        String remaining = normalized.substring(Math.min(normalized.length(), province.length()));
        String letter = remaining.isEmpty() ? "_" : firstCodePoint(remaining, "_").toUpperCase(Locale.ROOT);
        String suffix = remaining.substring(Math.min(remaining.length(), letter.length()));
        if (suffix.isBlank()) suffix = "UNKNOWN";
        return root.resolve("vehicles")
                .resolve(safePathSegment(province))
                .resolve(safePathSegment(letter))
                .resolve(safePathSegment(suffix) + ".json");
    }

    private String firstCodePoint(String value, String fallback) {
        if (value == null || value.isEmpty()) return fallback;
        int codePoint = value.codePointAt(0);
        return new String(Character.toChars(codePoint));
    }

    private String safePathSegment(String value) {
        String safe = safe(value).replaceAll("[<>:\"/\\\\|?*]", "_").trim();
        return safe.isBlank() ? "UNKNOWN" : safe;
    }

    private String fingerprint(ExternalOrderRecord record) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(objectMapper.writeValueAsBytes(record));
            StringBuilder result = new StringBuilder();
            for (int i = 0; i < 12; i++) result.append(String.format(Locale.ROOT, "%02x", bytes[i]));
            return result.toString();
        } catch (Exception exception) {
            return Integer.toHexString(record.hashCode());
        }
    }

    private void ensureLoaded() {
        if (loaded) return;
        loaded = true;
        if (!Files.isRegularFile(generalDatabasePath)) return;
        try {
            GeneralFile file = objectMapper.readValue(
                    generalDatabasePath.toFile(), new TypeReference<GeneralFile>() {});
            if (file != null && file.orders() != null) ordersByKey.putAll(file.orders());
        } catch (Exception exception) {
            throw new IllegalStateException("无法读取本地订单库: " + generalDatabasePath, exception);
        }
    }

    private void writeGeneralDatabase(Set<String> matchedVehicles) {
        List<String> completedKeys = ordersByKey.values().stream()
                .filter(order -> "COMPLETED".equals(category(order.latest())))
                .map(StoredOrder::key).toList();
        List<String> otherKeys = ordersByKey.values().stream()
                .filter(order -> "OTHER".equals(category(order.latest())))
                .map(StoredOrder::key).toList();
        writeJson(generalDatabasePath, new GeneralFile(
                SCHEMA_VERSION, Instant.now().toString(), completedKeys, otherKeys,
                List.copyOf(matchedVehicles), new LinkedHashMap<>(ordersByKey)));
    }

    private void writeJson(Path target, Object value) {
        try {
            Files.createDirectories(target.getParent());
            Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
            String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(value);
            Files.writeString(temporary, json, StandardCharsets.UTF_8);
            try {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception exception) {
            throw new IllegalStateException("无法写入本地订单库: " + target, exception);
        }
    }

    private Long parseTime(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return Instant.parse(value.trim()).toEpochMilli();
        } catch (Exception ignored) {
            // fall through
        }
        try {
            return LocalDateTime.parse(value.trim().replace(" ", "T"))
                    .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        } catch (Exception ignored) {
            return null;
        }
    }

    private String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : safe(second);
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }

    enum LifecycleStage { WAITING, RUNNING, COMPLETED, OTHER }

    public record IngestResult(
            int inputCount,
            int deduplicatedCount,
            int duplicateCount,
            int completedCount,
            int otherCount,
            int matchedVehicleCount,
            int addedObservationCount,
            int affectedVehicleCount,
            int completeLifecycleCount,
            String storeRoot,
            String generalDatabase
    ) {}

    public record GeneralFile(
            int schemaVersion,
            String updatedAt,
            List<String> completedOrderKeys,
            List<String> otherOrderKeys,
            List<String> matchedVehicleKeys,
            Map<String, StoredOrder> orders
    ) {}

    public record StoredOrder(
            String key,
            String orderId,
            String routeKey,
            String vehicleKey,
            String category,
            ExternalOrderRecord latest,
            List<StoredObservation> observations
    ) {}

    public record StoredObservation(
            String status,
            String stage,
            String semanticState,
            String updatedAt,
            Long eventTimeMs,
            Long effectiveEventTimeMs,
            String timeSource,
            long observedAtMs,
            String fingerprint,
            ExternalOrderRecord record
    ) {}

    public record VehicleFile(
            int schemaVersion,
            String plate,
            String updatedAt,
            List<VehicleOrderLifecycle> orders
    ) {}

    public record VehicleOrderLifecycle(
            String orderId,
            String routeKey,
            String lifecycleStatus,
            Long waitingAtMs,
            String waitingTimeSource,
            Long runningAtMs,
            Long completedAtMs,
            Long firstEventTimeMs,
            Long lastEventTimeMs,
            List<LifecycleEvent> events
    ) {}

    public record LifecycleEvent(
            String stage,
            String semanticState,
            String status,
            String updatedAt,
            Long eventTimeMs,
            Long effectiveEventTimeMs,
            String timeSource,
            long observedAtMs
    ) {}
}
