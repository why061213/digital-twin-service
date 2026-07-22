package com.jushen.digitaltwin.townroad;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 新中间层实验使用的两份本地库。
 *
 * <p>纯订单库按自然日分片，只把今天和昨天载入内存，并以“订单+路线+车辆”为键做快照 diff。
 * 车辆库是追加式轻量索引，每个订单只保存订单号、起点、终点和首次记录时间。</p>
 */
@Service
public class VehicleOrderChainStore {
    private static final Logger log = LoggerFactory.getLogger(VehicleOrderChainStore.class);
    private static final int SCHEMA_VERSION = 2;

    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final Path root;
    private final Path recordsRoot;
    private final Map<LocalDate, Map<String, StoredOrder>> dailyOrders = new LinkedHashMap<>();
    private final Map<String, StoredOrder> recentOrdersByKey = new LinkedHashMap<>();
    private final Map<String, Map<String, VehicleOrderEntry>> vehicleEntriesByPlate = new LinkedHashMap<>();
    private LocalDate loadedForDate;

    public VehicleOrderChainStore(ObjectMapper objectMapper, TownRoadExternalOrderProperties properties) {
        this(objectMapper, properties, Clock.systemDefaultZone());
    }

    VehicleOrderChainStore(
            ObjectMapper objectMapper,
            TownRoadExternalOrderProperties properties,
            Clock clock
    ) {
        this.objectMapper = objectMapper;
        this.clock = clock;
        String configuredPath = properties.getVehicleOrderChainStorePath();
        this.root = Path.of(configuredPath == null || configuredPath.isBlank()
                ? "runtime-data/vehicle-order-chain" : configuredPath).toAbsolutePath().normalize();
        this.recordsRoot = root.resolve("records");
        ensureRecentHistoryLoaded();
    }

    public synchronized IngestResult ingest(List<ExternalOrderRecord> records) {
        ensureRecentHistoryLoaded();
        long observedAt = clock.millis();
        LocalDate today = LocalDate.now(clock);
        List<ExternalOrderRecord> safeRecords = records == null ? List.of() : records;
        Map<String, ExternalOrderRecord> latestBatchRecords = deduplicateLatest(safeRecords);
        Map<String, StoredOrder> todayOrders = dailyOrders.computeIfAbsent(today, ignored -> new LinkedHashMap<>());

        int addedCount = 0;
        int updatedCount = 0;
        int unchangedCount = 0;
        int staleCount = 0;
        int completedCount = 0;
        int otherCount = 0;
        int vehicleOrderAddedCount = 0;
        Set<String> affectedPlates = new LinkedHashSet<>();
        Set<String> changedVehicleFiles = new LinkedHashSet<>();

        for (Map.Entry<String, ExternalOrderRecord> entry : latestBatchRecords.entrySet()) {
            String key = entry.getKey();
            ExternalOrderRecord record = entry.getValue();
            String plate = vehicleKey(record);
            affectedPlates.add(plate);
            if (isCompleted(record)) completedCount++; else otherCount++;

            StoredOrder current = recentOrdersByKey.get(key);
            if (current != null && compareRecordTime(record, current.record()) < 0) {
                staleCount++;
            } else if (current != null && sameRecord(record, current.record())) {
                unchangedCount++;
            } else {
                StoredOrder stored = new StoredOrder(
                        key,
                        safe(record.orderId()),
                        routeKey(record),
                        plate,
                        isCompleted(record) ? "COMPLETED" : "OTHER",
                        current == null ? observedAt : current.firstObservedAtMs(),
                        observedAt,
                        record
                );
                todayOrders.put(key, stored);
                recentOrdersByKey.put(key, stored);
                if (current == null) addedCount++; else updatedCount++;
            }

            if (appendMissingVehicleOrder(plate, record, observedAt)) {
                vehicleOrderAddedCount++;
                changedVehicleFiles.add(plate);
            }
        }

        if (addedCount + updatedCount > 0) writeDailyDatabase(today, todayOrders);
        for (String plate : changedVehicleFiles) writeVehicleFile(plate);

        Set<String> matchedVehicles = matchedVehicleKeys();
        IngestResult result = new IngestResult(
                safeRecords.size(), latestBatchRecords.size(),
                safeRecords.size() - latestBatchRecords.size(),
                addedCount, updatedCount, unchangedCount, staleCount,
                completedCount, otherCount, matchedVehicles.size(),
                vehicleOrderAddedCount, affectedPlates.size(),
                loadedDateStrings(), root.toString(), dailyFilePath(today).toString());
        log.warn("[VehicleOrderHistory][PIPELINE_CUT] diff stored and downstream stopped: {}", result);
        return result;
    }

    /** 供后续顶部统计直接使用，仅返回当前已加载的今天和昨天订单。 */
    public synchronized List<ExternalOrderRecord> recentOrders() {
        ensureRecentHistoryLoaded();
        return recentOrdersByKey.values().stream().map(StoredOrder::record).toList();
    }

    private void ensureRecentHistoryLoaded() {
        LocalDate today = LocalDate.now(clock);
        if (today.equals(loadedForDate)) return;
        dailyOrders.clear();
        recentOrdersByKey.clear();
        LocalDate yesterday = today.minusDays(1);
        loadDailyDatabase(yesterday);
        loadDailyDatabase(today);
        loadedForDate = today;
        log.info("[VehicleOrderHistory] startup history loaded: dates={}, records={}",
                loadedDateStrings(), recentOrdersByKey.size());
    }

    private void loadDailyDatabase(LocalDate date) {
        Path path = dailyFilePath(date);
        Map<String, StoredOrder> orders = new LinkedHashMap<>();
        if (Files.isRegularFile(path)) {
            try {
                DailyOrderFile file = objectMapper.readValue(path.toFile(), DailyOrderFile.class);
                if (file != null && file.orders() != null) orders.putAll(file.orders());
            } catch (Exception exception) {
                throw new IllegalStateException("无法读取日期订单库: " + path, exception);
            }
        }
        dailyOrders.put(date, orders);
        for (Map.Entry<String, StoredOrder> entry : orders.entrySet()) {
            StoredOrder current = recentOrdersByKey.get(entry.getKey());
            StoredOrder candidate = entry.getValue();
            if (current == null || compareStoredOrder(candidate, current) >= 0) {
                recentOrdersByKey.put(entry.getKey(), candidate);
            }
        }
    }

    private int compareStoredOrder(StoredOrder left, StoredOrder right) {
        int byBusinessTime = compareRecordTime(left.record(), right.record());
        if (byBusinessTime != 0) return byBusinessTime;
        return Long.compare(left.lastObservedAtMs(), right.lastObservedAtMs());
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
        Long leftTime = parseTime(left == null ? null : left.updatedAt());
        Long rightTime = parseTime(right == null ? null : right.updatedAt());
        if (leftTime != null && rightTime != null) return Long.compare(leftTime, rightTime);
        if (leftTime != null) return 1;
        if (rightTime != null) return -1;
        return safe(left == null ? null : left.updatedAt())
                .compareTo(safe(right == null ? null : right.updatedAt()));
    }

    private boolean sameRecord(ExternalOrderRecord left, ExternalOrderRecord right) {
        try {
            return objectMapper.valueToTree(left).equals(objectMapper.valueToTree(right));
        } catch (Exception ignored) {
            return left != null && left.equals(right);
        }
    }

    private boolean appendMissingVehicleOrder(
            String plate,
            ExternalOrderRecord record,
            long observedAt
    ) {
        String orderId = safe(record.orderId());
        if (orderId.isBlank()) return false;
        Map<String, VehicleOrderEntry> entries = loadVehicleEntries(plate);
        if (entries.containsKey(orderId)) return false;
        entries.put(orderId, new VehicleOrderEntry(
                orderId,
                locationDisplay(record.from()),
                locationDisplay(record.to()),
                normalizedOrderTime(record.updatedAt(), observedAt)
        ));
        return true;
    }

    private Map<String, VehicleOrderEntry> loadVehicleEntries(String plate) {
        Map<String, VehicleOrderEntry> cached = vehicleEntriesByPlate.get(plate);
        if (cached != null) return cached;
        Map<String, VehicleOrderEntry> entries = new LinkedHashMap<>();
        Path path = vehicleFilePath(plate);
        if (Files.isRegularFile(path)) {
            try {
                VehicleFile file = objectMapper.readValue(path.toFile(), VehicleFile.class);
                if (file != null && file.orders() != null) {
                    for (VehicleOrderEntry entry : file.orders()) {
                        if (entry != null && entry.orderId() != null) entries.putIfAbsent(entry.orderId(), entry);
                    }
                }
            } catch (Exception exception) {
                throw new IllegalStateException("无法读取车辆订单库: " + path, exception);
            }
        }
        vehicleEntriesByPlate.put(plate, entries);
        return entries;
    }

    private void writeVehicleFile(String plate) {
        Map<String, VehicleOrderEntry> entries = vehicleEntriesByPlate.get(plate);
        if (entries == null) return;
        List<VehicleOrderEntry> sorted = new ArrayList<>(entries.values());
        sorted.sort(Comparator
                .comparing((VehicleOrderEntry entry) -> parseTime(entry.time()) == null)
                .thenComparing(entry -> parseTime(entry.time()) == null
                        ? Long.MAX_VALUE : parseTime(entry.time()))
                .thenComparing(VehicleOrderEntry::orderId));
        writeJson(vehicleFilePath(plate), new VehicleFile(List.copyOf(sorted)));
    }

    private void writeDailyDatabase(LocalDate date, Map<String, StoredOrder> orders) {
        List<String> completedKeys = orders.values().stream()
                .filter(order -> "COMPLETED".equals(order.category()))
                .map(StoredOrder::key).toList();
        List<String> otherKeys = orders.values().stream()
                .filter(order -> "OTHER".equals(order.category()))
                .map(StoredOrder::key).toList();
        writeJson(dailyFilePath(date), new DailyOrderFile(
                SCHEMA_VERSION, date.toString(), Instant.ofEpochMilli(clock.millis()).toString(),
                completedKeys, otherKeys, new LinkedHashMap<>(orders)));
    }

    private Set<String> matchedVehicleKeys() {
        Set<String> completed = new LinkedHashSet<>();
        Set<String> other = new LinkedHashSet<>();
        for (StoredOrder order : recentOrdersByKey.values()) {
            if ("COMPLETED".equals(order.category())) completed.add(order.vehicleKey());
            else other.add(order.vehicleKey());
        }
        completed.retainAll(other);
        return completed;
    }

    private List<String> loadedDateStrings() {
        return dailyOrders.keySet().stream().map(LocalDate::toString).toList();
    }

    private Path dailyFilePath(LocalDate date) {
        return recordsRoot.resolve(date + ".json");
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

    private String locationDisplay(ExternalOrderRecord.Location location) {
        if (location == null) return "";
        if (location.name() != null && !location.name().isBlank()) return location.name().trim();
        String result = safe(location.province()) + safe(location.city()) + safe(location.district());
        return result.isBlank() ? safe(location.adcode()) : result;
    }

    private String vehicleKey(ExternalOrderRecord record) {
        ExternalOrderRecord.Vehicle vehicle = record.vehicle();
        String candidate = vehicle == null ? "" : firstNonBlank(vehicle.plate(), vehicle.carId());
        String normalized = candidate.trim().toUpperCase(Locale.ROOT)
                .replaceAll("[\\s.·•・—–_-]", "");
        return normalized.isBlank() ? "UNKNOWN" : normalized;
    }

    private boolean isCompleted(ExternalOrderRecord record) {
        String normalized = safe(record == null ? null : record.status()).replace(" ", "");
        return normalized.contains("已完成") || normalized.equals("完成");
    }

    private String normalizedOrderTime(String upstreamTime, long observedAt) {
        Long parsed = parseTime(upstreamTime);
        return parsed == null
                ? Instant.ofEpochMilli(observedAt).toString()
                : Instant.ofEpochMilli(parsed).toString();
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
                    .atZone(clock.getZone()).toInstant().toEpochMilli();
        } catch (Exception ignored) {
            return null;
        }
    }

    private String firstCodePoint(String value, String fallback) {
        if (value == null || value.isEmpty()) return fallback;
        return new String(Character.toChars(value.codePointAt(0)));
    }

    private String safePathSegment(String value) {
        String safe = safe(value).replaceAll("[<>:\"/\\\\|?*]", "_").trim();
        return safe.isBlank() ? "UNKNOWN" : safe;
    }

    private String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : safe(second);
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }

    public record IngestResult(
            int inputCount,
            int deduplicatedCount,
            int duplicateCount,
            int addedCount,
            int updatedCount,
            int unchangedCount,
            int staleCount,
            int completedCount,
            int otherCount,
            int matchedVehicleCount,
            int vehicleOrderAddedCount,
            int affectedVehicleCount,
            List<String> loadedDates,
            String storeRoot,
            String currentDailyDatabase
    ) {}

    public record DailyOrderFile(
            int schemaVersion,
            String date,
            String updatedAt,
            List<String> completedOrderKeys,
            List<String> otherOrderKeys,
            Map<String, StoredOrder> orders
    ) {}

    public record StoredOrder(
            String key,
            String orderId,
            String routeKey,
            String vehicleKey,
            String category,
            long firstObservedAtMs,
            long lastObservedAtMs,
            ExternalOrderRecord record
    ) {}

    public record VehicleFile(List<VehicleOrderEntry> orders) {}

    /** 车辆索引中的订单项严格只保留这四个业务字段。 */
    public record VehicleOrderEntry(
            String orderId,
            String from,
            String to,
            String time
    ) {}
}
