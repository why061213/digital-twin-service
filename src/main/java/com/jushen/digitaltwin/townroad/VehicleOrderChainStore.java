package com.jushen.digitaltwin.townroad;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Duration;
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
import java.util.stream.Stream;

/**
 * 新中间层实验使用的两份本地库。
 *
 * <p>纯订单库按自然日分片，只把今天和昨天载入内存，并以“订单+路线+车辆”为键做快照 diff。
 * 车辆库是追加式轻量索引，每个订单只保存订单号、起点、终点、状态和该状态的首次记录时间。</p>
 */
@Service
public class VehicleOrderChainStore {
    private static final Logger log = LoggerFactory.getLogger(VehicleOrderChainStore.class);
    private static final int SCHEMA_VERSION = 2;
    private static final String STATUS_WAITING = "待装载";
    private static final String STATUS_TRANSIT_CONFIRMED = "在途-1";
    private static final String STATUS_TRANSIT_INFERRED = "在途-2";
    private static final String STATUS_COMPLETED_LEGACY = "已完成";
    private static final String STATUS_COMPLETED_CONFIRMED = "已完成-1";
    private static final String STATUS_COMPLETED_INFERRED = "已完成-2";
    private static final Duration TRACKING_HISTORY_RETENTION = Duration.ofHours(48);

    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final Path root;
    private final Path recordsRoot;
    private final Path trackingIndexPath;
    private final Map<LocalDate, Map<String, StoredOrder>> dailyOrders = new LinkedHashMap<>();
    private final Map<String, StoredOrder> recentOrdersByKey = new LinkedHashMap<>();
    private final Map<String, Map<String, VehicleOrderEntry>> vehicleEntriesByPlate = new LinkedHashMap<>();
    private final Set<String> latestIngestKeys = new LinkedHashSet<>();
    private LocalDate loadedForDate;

    @Autowired
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
        this.trackingIndexPath = root.resolve("tracking-active-index.json");
        ensureRecentHistoryLoaded();
    }

    public synchronized IngestResult ingest(List<ExternalOrderRecord> records) {
        ensureRecentHistoryLoaded();
        long observedAt = clock.millis();
        LocalDate today = LocalDate.now(clock);
        List<ExternalOrderRecord> safeRecords = records == null ? List.of() : records;
        Map<String, ExternalOrderRecord> latestBatchRecords = deduplicateLatest(safeRecords);
        latestIngestKeys.clear();
        latestIngestKeys.addAll(latestBatchRecords.keySet());
        Map<String, StoredOrder> todayOrders = dailyOrders.computeIfAbsent(today, ignored -> readDailyOrders(today));
        if (todayOrders.isEmpty() && Files.isRegularFile(dailyFilePath(today))) {
            // 活跃索引启动时未载入完整审计日库；首次写入前再懒加载，避免覆盖历史记录。
            todayOrders.putAll(readDailyOrders(today));
        }

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
            boolean effectivelyCompleted = isCompleted(record) || recordedCompletionStatus(record) != null;
            if (effectivelyCompleted) completedCount++; else otherCount++;

            // 纯订单快照 diff 只比较上游订单；车辆库中的“在途-2”是本地推断事件，
            // 不得因上游仍返回同一条“待装载”而触发订单更新或覆盖推断状态。
            StoredOrder current = recentOrdersByKey.get(key);
            if (current != null && compareRecordTime(record, current.record()) < 0) {
                staleCount++;
            } else if (current != null && sameRecord(record, current.record())
                    && effectivelyCompleted == "COMPLETED".equals(current.category())) {
                unchangedCount++;
            } else {
                StoredOrder stored = new StoredOrder(
                        key,
                        safe(record.orderId()),
                        routeKey(record),
                        plate,
                        effectivelyCompleted ? "COMPLETED" : "OTHER",
                        current == null ? observedAt : current.firstObservedAtMs(),
                        observedAt,
                        record
                );
                todayOrders.put(key, stored);
                recentOrdersByKey.put(key, stored);
                if (current == null) addedCount++; else updatedCount++;
            }

            if (appendExternalVehicleStatus(plate, record, observedAt)) {
                vehicleOrderAddedCount++;
                changedVehicleFiles.add(plate);
            }
        }

        if (addedCount + updatedCount > 0) writeDailyDatabase(today, todayOrders);
        for (String plate : changedVehicleFiles) writeVehicleFile(plate);
        writeTrackingIndex();

        Set<String> matchedVehicles = matchedVehicleKeys();
        IngestResult result = new IngestResult(
                safeRecords.size(), latestBatchRecords.size(),
                safeRecords.size() - latestBatchRecords.size(),
                addedCount, updatedCount, unchangedCount, staleCount,
                completedCount, otherCount, matchedVehicles.size(),
                vehicleOrderAddedCount, affectedPlates.size(),
                loadedDateStrings(), root.toString(), dailyFilePath(today).toString());
        log.info("[VehicleOrderHistory] diff stored: {}", result);
        return result;
    }

    /** 供后续顶部统计直接使用，仅返回当前已加载的今天和昨天订单。 */
    public synchronized List<ExternalOrderRecord> recentOrders() {
        ensureRecentHistoryLoaded();
        return recentOrdersByKey.values().stream().map(StoredOrder::record).toList();
    }

    /** 当前今昨窗口的去重记录，包含本地首次/最后观测时间，供车辆订单链判定使用。 */
    public synchronized List<StoredOrder> recentStoredOrders() {
        ensureRecentHistoryLoaded();
        return List.copyOf(recentOrdersByKey.values());
    }

    /** 最近一次上游快照实际出现的订单，避免把今昨库中已消失的旧未完成订单并入当前任务簇。 */
    public synchronized List<StoredOrder> latestObservedStoredOrders() {
        ensureRecentHistoryLoaded();
        return latestIngestKeys.stream()
                .map(recentOrdersByKey::get)
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    /** 车辆 Trip 只消费活跃索引，不再扫描纯审计日库中的全部历史完成单。 */
    public synchronized List<StoredOrder> activeTrackingStoredOrders() {
        ensureRecentHistoryLoaded();
        return trackingRelevantOrders(recentOrdersByKey.values());
    }

    Path runtimeRootPath() {
        return root;
    }

    /** 轨迹证据判定车辆已离开装载点时，追加“疑似在途”事件。 */
    public synchronized boolean recordSuspectedInTransit(ExternalOrderRecord record) {
        if (record == null) return false;
        String plate = vehicleKey(record);
        boolean changed = appendVehicleStatus(
                plate, record, STATUS_TRANSIT_INFERRED, clock.millis(), false);
        if (changed) writeVehicleFile(plate);
        return changed;
    }

    /** 返回该车该订单已记录的在途级别，外部确认的在途-1优先。 */
    public synchronized String recordedTransitStatus(ExternalOrderRecord record) {
        if (record == null || safe(record.orderId()).isBlank()) return null;
        Map<String, VehicleOrderEntry> entries = loadVehicleEntries(vehicleKey(record));
        String orderId = safe(record.orderId());
        if (entries.containsKey(vehicleEventKey(orderId, STATUS_TRANSIT_CONFIRMED))) {
            return STATUS_TRANSIT_CONFIRMED;
        }
        if (entries.containsKey(vehicleEventKey(orderId, STATUS_TRANSIT_INFERRED))) {
            return STATUS_TRANSIT_INFERRED;
        }
        return null;
    }

    /** 目的地持续驻留达到阈值时追加轨迹推定完成事件；不改写后续正式确认。 */
    public synchronized boolean recordInferredCompletion(ExternalOrderRecord record) {
        if (record == null) return false;
        String plate = vehicleKey(record);
        boolean changed = appendVehicleStatus(
                plate, record, STATUS_COMPLETED_INFERRED, clock.millis(), false);
        if (changed) {
            writeVehicleFile(plate);
            writeTrackingIndex();
        }
        return changed;
    }

    /** 返回订单链的完成级别；正式确认优先，但已完成-2会作为独立审计事件永久保留。 */
    public synchronized String recordedCompletionStatus(ExternalOrderRecord record) {
        if (record == null || safe(record.orderId()).isBlank()) return null;
        Map<String, VehicleOrderEntry> entries = loadVehicleEntries(vehicleKey(record));
        String orderId = safe(record.orderId());
        if (entries.containsKey(vehicleEventKey(orderId, STATUS_COMPLETED_CONFIRMED))
                || entries.containsKey(vehicleEventKey(orderId, STATUS_COMPLETED_LEGACY))) {
            return STATUS_COMPLETED_CONFIRMED;
        }
        if (entries.containsKey(vehicleEventKey(orderId, STATUS_COMPLETED_INFERRED))) {
            return STATUS_COMPLETED_INFERRED;
        }
        return null;
    }

    /** 扫描持久化车辆状态链，统计在途-2 及后续在途-1 的时间差。 */
    public synchronized TransitMetrics transitMetrics() {
        Path vehiclesRoot = root.resolve("vehicles");
        List<SuspectedTransitDetail> details = new ArrayList<>();
        if (Files.isDirectory(vehiclesRoot)) {
            try (Stream<Path> paths = Files.walk(vehiclesRoot)) {
                for (Path path : paths.filter(Files::isRegularFile)
                        .filter(item -> item.getFileName().toString().endsWith(".json")).toList()) {
                    collectTransitMetrics(vehiclesRoot, path, details);
                }
            } catch (Exception exception) {
                throw new IllegalStateException("无法扫描车辆状态链: " + vehiclesRoot, exception);
            }
        }

        details.sort(Comparator
                .comparing((SuspectedTransitDetail detail) -> parseTime(detail.suspectedAt()) == null)
                .thenComparing(detail -> parseTime(detail.suspectedAt()) == null
                        ? Long.MIN_VALUE : -parseTime(detail.suspectedAt()))
                .thenComparing(SuspectedTransitDetail::plate)
                .thenComparing(SuspectedTransitDetail::orderId));

        List<Long> validIntervals = details.stream()
                .map(SuspectedTransitDetail::confirmationIntervalSeconds)
                .filter(value -> value != null && value >= 0)
                .toList();
        long confirmedCount = details.stream().filter(SuspectedTransitDetail::upstreamConfirmed).count();
        long invalidTimeOrderCount = details.stream()
                .filter(SuspectedTransitDetail::upstreamConfirmed)
                .filter(detail -> !Boolean.TRUE.equals(detail.timeOrderValid()))
                .count();
        Long minSeconds = validIntervals.stream().min(Long::compareTo).orElse(null);
        Long maxSeconds = validIntervals.stream().max(Long::compareTo).orElse(null);
        Double averageSeconds = validIntervals.isEmpty() ? null
                : validIntervals.stream().mapToLong(Long::longValue).average().orElse(0d);
        IntervalStatistics intervals = new IntervalStatistics(
                minSeconds,
                maxSeconds,
                minSeconds == null ? null : roundToTwoDecimals(minSeconds / 60d),
                maxSeconds == null ? null : roundToTwoDecimals(maxSeconds / 60d),
                averageSeconds == null ? null : roundToTwoDecimals(averageSeconds),
                averageSeconds == null ? null : roundToTwoDecimals(averageSeconds / 60d)
        );
        return new TransitMetrics(
                Instant.ofEpochMilli(clock.millis()).toString(),
                root.toString(),
                details.size(),
                confirmedCount,
                details.size() - confirmedCount,
                validIntervals.size(),
                invalidTimeOrderCount,
                intervals,
                List.copyOf(details)
        );
    }

    private void collectTransitMetrics(
            Path vehiclesRoot,
            Path path,
            List<SuspectedTransitDetail> details
    ) {
        try {
            VehicleFile file = objectMapper.readValue(path.toFile(), VehicleFile.class);
            if (file == null || file.orders() == null) return;
            Map<String, List<VehicleOrderEntry>> byOrderId = new LinkedHashMap<>();
            for (VehicleOrderEntry entry : file.orders()) {
                if (entry == null || safe(entry.orderId()).isBlank()) continue;
                byOrderId.computeIfAbsent(safe(entry.orderId()), ignored -> new ArrayList<>()).add(entry);
            }
            String plate = plateFromVehicleFile(vehiclesRoot, path);
            for (Map.Entry<String, List<VehicleOrderEntry>> order : byOrderId.entrySet()) {
                VehicleOrderEntry suspected = statusEntry(order.getValue(), STATUS_TRANSIT_INFERRED);
                if (suspected == null) continue;
                VehicleOrderEntry confirmed = statusEntry(order.getValue(), STATUS_TRANSIT_CONFIRMED);
                Long suspectedMs = parseTime(suspected.time());
                Long confirmedMs = confirmed == null ? null : parseTime(confirmed.time());
                Long intervalSeconds = suspectedMs == null || confirmedMs == null
                        ? null : Duration.ofMillis(confirmedMs - suspectedMs).toSeconds();
                Boolean timeOrderValid = confirmed == null ? null
                        : intervalSeconds != null && intervalSeconds >= 0;
                details.add(new SuspectedTransitDetail(
                        plate,
                        order.getKey(),
                        suspected.from(),
                        suspected.to(),
                        suspected.time(),
                        confirmed == null ? null : confirmed.time(),
                        confirmed != null,
                        intervalSeconds,
                        intervalSeconds == null ? null : roundToTwoDecimals(intervalSeconds / 60d),
                        timeOrderValid
                ));
            }
        } catch (Exception exception) {
            log.warn("[VehicleOrderHistory] skip unreadable vehicle state file: path={}", path, exception);
        }
    }

    private VehicleOrderEntry statusEntry(List<VehicleOrderEntry> entries, String status) {
        if (entries == null) return null;
        return entries.stream().filter(entry -> status.equals(entry.status())).findFirst().orElse(null);
    }

    private String plateFromVehicleFile(Path vehiclesRoot, Path path) {
        Path relative = vehiclesRoot.relativize(path);
        if (relative.getNameCount() < 3) return "UNKNOWN";
        String suffix = relative.getName(2).toString().replaceFirst("\\.json$", "");
        return relative.getName(0) + relative.getName(1).toString() + suffix;
    }

    private double roundToTwoDecimals(double value) {
        return Math.round(value * 100d) / 100d;
    }

    /** 判定结果属于实验诊断数据，不混入纯订单日库和车辆轻量索引。 */
    public synchronized String writeEligibilityAnalysis(VehicleOrderEligibilityService.EligibilityReport report) {
        Path path = root.resolve("analysis").resolve(LocalDate.now(clock) + ".json");
        writeJson(path, report);
        return path.toString();
    }

    private void ensureRecentHistoryLoaded() {
        LocalDate today = LocalDate.now(clock);
        if (today.equals(loadedForDate)) return;
        dailyOrders.clear();
        recentOrdersByKey.clear();
        LocalDate yesterday = today.minusDays(1);
        if (!loadTrackingIndex(today)) {
            loadDailyDatabase(yesterday);
            loadDailyDatabase(today);
            writeTrackingIndex();
        } else {
            dailyOrders.put(yesterday, new LinkedHashMap<>());
            dailyOrders.put(today, new LinkedHashMap<>());
        }
        loadedForDate = today;
        log.info("[VehicleOrderHistory] startup history loaded: dates={}, records={}",
                loadedDateStrings(), recentOrdersByKey.size());
    }

    private boolean loadTrackingIndex(LocalDate today) {
        if (!Files.isRegularFile(trackingIndexPath)) return false;
        try {
            TrackingOrderIndex index = objectMapper.readValue(trackingIndexPath.toFile(), TrackingOrderIndex.class);
            if (index == null || index.schemaVersion() != SCHEMA_VERSION
                    || index.generatedDate() == null || !today.toString().equals(index.generatedDate())
                    || index.orders() == null) return false;
            recentOrdersByKey.putAll(index.orders());
            return true;
        } catch (Exception exception) {
            log.warn("[VehicleOrderHistory] active tracking index unavailable, falling back to daily files: {}",
                    trackingIndexPath, exception);
            return false;
        }
    }

    private void writeTrackingIndex() {
        List<StoredOrder> relevant = trackingRelevantOrders(recentOrdersByKey.values());
        Map<String, StoredOrder> indexed = new LinkedHashMap<>();
        for (StoredOrder order : relevant) indexed.put(order.key(), order);
        writeJson(trackingIndexPath, new TrackingOrderIndex(
                SCHEMA_VERSION, LocalDate.now(clock).toString(),
                Instant.ofEpochMilli(clock.millis()).toString(), indexed));
    }

    private List<StoredOrder> trackingRelevantOrders(java.util.Collection<StoredOrder> orders) {
        long cutoff = clock.millis() - TRACKING_HISTORY_RETENTION.toMillis();
        Set<String> vehiclesWithOpenOrders = orders.stream()
                .filter(order -> !"COMPLETED".equals(order.category()))
                .map(StoredOrder::vehicleKey)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        Map<String, StoredOrder> latestCompletedByVehicle = new LinkedHashMap<>();
        List<StoredOrder> result = new ArrayList<>();
        for (StoredOrder order : orders) {
            if (!"COMPLETED".equals(order.category())) {
                result.add(order);
                continue;
            }
            if (order.lastObservedAtMs() < cutoff && !vehiclesWithOpenOrders.contains(order.vehicleKey())) continue;
            StoredOrder current = latestCompletedByVehicle.get(order.vehicleKey());
            if (current == null || compareStoredOrder(order, current) > 0) {
                latestCompletedByVehicle.put(order.vehicleKey(), order);
            }
        }
        result.addAll(latestCompletedByVehicle.values());
        result.sort(Comparator.comparingLong(StoredOrder::firstObservedAtMs).thenComparing(StoredOrder::key));
        return List.copyOf(result);
    }

    private void loadDailyDatabase(LocalDate date) {
        Map<String, StoredOrder> orders = readDailyOrders(date);
        dailyOrders.put(date, orders);
        for (Map.Entry<String, StoredOrder> entry : orders.entrySet()) {
            StoredOrder current = recentOrdersByKey.get(entry.getKey());
            StoredOrder candidate = entry.getValue();
            if (current == null || compareStoredOrder(candidate, current) >= 0) {
                recentOrdersByKey.put(entry.getKey(), candidate);
            }
        }
    }

    private Map<String, StoredOrder> readDailyOrders(LocalDate date) {
        Path path = dailyFilePath(date);
        Map<String, StoredOrder> orders = new LinkedHashMap<>();
        if (!Files.isRegularFile(path)) return orders;
        try {
            DailyOrderFile file = objectMapper.readValue(path.toFile(), DailyOrderFile.class);
            if (file != null && file.orders() != null) orders.putAll(file.orders());
            return orders;
        } catch (Exception exception) {
            throw new IllegalStateException("无法读取日期订单库: " + path, exception);
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

    private boolean appendExternalVehicleStatus(
            String plate,
            ExternalOrderRecord record,
            long observedAt
    ) {
        String status = externalVehicleStatus(record == null ? null : record.status());
        if (status == null) return false;
        return appendVehicleStatus(plate, record, status, observedAt, true);
    }

    private boolean appendVehicleStatus(
            String plate,
            ExternalOrderRecord record,
            String status,
            long observedAt,
            boolean useUpstreamTime
    ) {
        String orderId = safe(record.orderId());
        if (orderId.isBlank()) return false;
        Map<String, VehicleOrderEntry> entries = loadVehicleEntries(plate);
        String eventKey = vehicleEventKey(orderId, status);
        if (entries.containsKey(eventKey)) return false;

        // 旧版文件没有 status：该订单再次被观测时，用当前可确认状态就地升级。
        VehicleOrderEntry legacy = entries.remove(vehicleEventKey(orderId, null));
        entries.put(eventKey, new VehicleOrderEntry(
                orderId,
                legacy == null ? locationDisplay(record.from()) : legacy.from(),
                legacy == null ? locationDisplay(record.to()) : legacy.to(),
                legacy == null
                        ? normalizedOrderTime(useUpstreamTime ? record.updatedAt() : null, observedAt)
                        : legacy.time(),
                status
        ));
        return true;
    }

    private String externalVehicleStatus(String rawStatus) {
        String status = safe(rawStatus).replace(" ", "");
        if (status.contains("已完成") || status.equals("完成")) return STATUS_COMPLETED_CONFIRMED;
        if (status.contains("运输中") || status.contains("运行中") || status.contains("在途")) {
            return STATUS_TRANSIT_CONFIRMED;
        }
        if (status.contains("待装载") || status.contains("待装货")
                || status.contains("装载中") || status.contains("装货中")) {
            return STATUS_WAITING;
        }
        return null;
    }

    private String vehicleEventKey(String orderId, String status) {
        return safe(orderId) + "|" + safe(status);
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
                        if (entry != null && entry.orderId() != null) {
                            entries.putIfAbsent(vehicleEventKey(entry.orderId(), entry.status()), entry);
                        }
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
                .thenComparing(VehicleOrderEntry::orderId)
                .thenComparing(entry -> safe(entry.status())));
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

    public record TrackingOrderIndex(
            int schemaVersion,
            String generatedDate,
            String updatedAt,
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

    public record TransitMetrics(
            String generatedAt,
            String storeRoot,
            int suspectedTransitCount,
            long upstreamConfirmedCount,
            long awaitingUpstreamConfirmationCount,
            int measurableIntervalCount,
            long invalidTimeOrderCount,
            IntervalStatistics confirmationInterval,
            List<SuspectedTransitDetail> details
    ) {}

    public record IntervalStatistics(
            Long minSeconds,
            Long maxSeconds,
            Double minMinutes,
            Double maxMinutes,
            Double averageSeconds,
            Double averageMinutes
    ) {}

    public record SuspectedTransitDetail(
            String plate,
            String orderId,
            String from,
            String to,
            String suspectedAt,
            String upstreamConfirmedAt,
            boolean upstreamConfirmed,
            Long confirmationIntervalSeconds,
            Double confirmationIntervalMinutes,
            Boolean timeOrderValid
    ) {}

    /** 车辆索引中的订单状态事件严格只保留这五个业务字段。 */
    public record VehicleOrderEntry(
            String orderId,
            String from,
            String to,
            String time,
            String status
    ) {}
}
