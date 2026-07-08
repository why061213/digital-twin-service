package com.jushen.digitaltwin.externalorder;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class ExternalOrderStore {

    private final ObjectMapper objectMapper;

    private long batchId = 0;

    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    private final Map<String, ExternalOrderRoute> routesByLineId = new ConcurrentHashMap<>();

    /**
     * fromKey -> toKey -> lineIds
     */
    private final Map<String, Map<String, LinkedHashSet<String>>> odIndex = new ConcurrentHashMap<>();

    public ExternalOrderStore(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public synchronized ExternalOrderDiff applySnapshot(
            List<ExternalOrderRecord> records,
            boolean fullSnapshot
    ) {
        batchId++;

        List<ExternalOrderRoute> added = new ArrayList<>();
        List<ExternalOrderRoute> updated = new ArrayList<>();
        List<ExternalOrderRoute> deleted = new ArrayList<>();
        List<ExternalOrderRoute> unchanged = new ArrayList<>();
        List<ExternalOrderRoute> routeChanged = new ArrayList<>();

        for (ExternalOrderRecord record : records) {
            if (!isValid(record)) {
                continue;
            }

            String lineId = record.lineId();
            CacheEntry oldEntry = cache.get(lineId);

            if (Boolean.TRUE.equals(record.deleted()) || "已取消".equals(record.status())) {
                if (oldEntry != null) {
                    removeRoute(lineId);
                    cache.remove(lineId);
                    deleted.add(oldEntry.route());
                }
                continue;
            }

            ExternalOrderRoute nextRoute = normalize(record);
            String nextDataSignature = nextRoute.dataSignature();
            String nextRouteSignature = nextRoute.routeSignature();

            if (oldEntry == null) {
                CacheEntry nextEntry = new CacheEntry(
                        nextRoute,
                        nextDataSignature,
                        nextRouteSignature,
                        record.updatedAt(),
                        batchId
                );

                cache.put(lineId, nextEntry);
                addRoute(nextRoute);
                added.add(nextRoute);
                continue;
            }

            boolean dataChanged = !oldEntry.dataSignature().equals(nextDataSignature);
            boolean routeHasChanged = !oldEntry.routeSignature().equals(nextRouteSignature);

            if (!dataChanged && !routeHasChanged) {
                oldEntry.lastSeenBatch(batchId);
                unchanged.add(oldEntry.route());
                continue;
            }

            removeRoute(lineId);

            CacheEntry nextEntry = new CacheEntry(
                    nextRoute,
                    nextDataSignature,
                    nextRouteSignature,
                    record.updatedAt(),
                    batchId
            );

            cache.put(lineId, nextEntry);
            addRoute(nextRoute);

            updated.add(nextRoute);

            if (routeHasChanged) {
                routeChanged.add(nextRoute);
            }
        }

        if (fullSnapshot) {
            List<String> missingLineIds = new ArrayList<>();

            for (Map.Entry<String, CacheEntry> entry : cache.entrySet()) {
                if (entry.getValue().lastSeenBatch() != batchId) {
                    missingLineIds.add(entry.getKey());
                }
            }

            for (String lineId : missingLineIds) {
                CacheEntry oldEntry = cache.remove(lineId);
                if (oldEntry != null) {
                    removeRoute(lineId);
                    deleted.add(oldEntry.route());
                }
            }
        }

        return new ExternalOrderDiff(
                added,
                updated,
                deleted,
                unchanged,
                routeChanged
        );
    }

    public List<ExternalOrderRoute> allRoutes() {
        return routesByLineId.values()
                .stream()
                .sorted((a, b) -> String.valueOf(a.updatedAt()).compareTo(String.valueOf(b.updatedAt())))
                .toList();
    }

    public ExternalOrderRoute getByLineId(String lineId) {
        return routesByLineId.get(lineId);
    }

    public List<ExternalOrderRoute> findSameOdOrders(String fromKey, String toKey) {
        LinkedHashSet<String> lineIds = odIndex
                .getOrDefault(fromKey, Collections.emptyMap())
                .get(toKey);

        if (lineIds == null || lineIds.isEmpty()) {
            return List.of();
        }

        return lineIds.stream()
                .map(routesByLineId::get)
                .filter(route -> route != null)
                .toList();
    }

    public List<ExternalOrderRoute> findOrdersAlongRoute(List<String> routeNodeKeys) {
        Map<String, Integer> nodeIndex = new LinkedHashMap<>();
        for (int i = 0; i < routeNodeKeys.size(); i++) {
            nodeIndex.put(routeNodeKeys.get(i), i);
        }

        Map<String, ExternalOrderRoute> result = new LinkedHashMap<>();

        for (int i = 0; i < routeNodeKeys.size(); i++) {
            String fromKey = routeNodeKeys.get(i);
            Map<String, LinkedHashSet<String>> toMap = odIndex.get(fromKey);
            if (toMap == null) continue;

            for (Map.Entry<String, LinkedHashSet<String>> entry : toMap.entrySet()) {
                String toKey = entry.getKey();
                Integer toIndex = nodeIndex.get(toKey);

                if (toIndex == null) continue;
                if (toIndex <= i) continue;

                for (String lineId : entry.getValue()) {
                    ExternalOrderRoute route = routesByLineId.get(lineId);
                    if (route != null) {
                        result.put(lineId, route);
                    }
                }
            }
        }

        return new ArrayList<>(result.values());
    }

    private void addRoute(ExternalOrderRoute route) {
        routesByLineId.put(route.lineId(), route);

        odIndex
                .computeIfAbsent(route.fromKey(), ignored -> new ConcurrentHashMap<>())
                .computeIfAbsent(route.toKey(), ignored -> new LinkedHashSet<>())
                .add(route.lineId());
    }

    private void removeRoute(String lineId) {
        ExternalOrderRoute oldRoute = routesByLineId.remove(lineId);
        if (oldRoute == null) return;

        Map<String, LinkedHashSet<String>> toMap = odIndex.get(oldRoute.fromKey());
        if (toMap == null) return;

        LinkedHashSet<String> lineIds = toMap.get(oldRoute.toKey());
        if (lineIds != null) {
            lineIds.remove(lineId);

            if (lineIds.isEmpty()) {
                toMap.remove(oldRoute.toKey());
            }
        }

        if (toMap.isEmpty()) {
            odIndex.remove(oldRoute.fromKey());
        }
    }

    private ExternalOrderRoute normalize(ExternalOrderRecord record) {
        String fromKey = locationKey(record.from());
        String toKey = locationKey(record.to());

        String fromName = locationName(record.from());
        String toName = locationName(record.to());

        double[] fromCoords = record.from().coords();
        double[] toCoords = record.to().coords();

        List<double[]> coordinates = hasCoords(fromCoords) && hasCoords(toCoords)
                ? List.of(fromCoords, toCoords)
                : List.of();

        double routeLengthKm = coordinates.size() >= 2 ? distanceKm(fromCoords, toCoords) : 0;
        double speedKmh = record.vehicle().speedKmh() == null ? 0 : record.vehicle().speedKmh();
        long travelDurationMs = speedKmh > 0 && routeLengthKm > 0
                ? Math.max(60_000L, Math.round(routeLengthKm / speedKmh * 3_600_000))
                : 60_000L;

        String orderId = blankToNull(record.orderId());
        String pathKey = fromKey + ">" + toKey;
        List<String> segmentKeys = List.of(fromKey + "->" + toKey);

        String dataSignature = signature(Map.of(
                "orderId", nvl(record.orderId()),
                "lineId", nvl(record.lineId()),
                "fromKey", fromKey,
                "toKey", toKey,
                "plate", nvl(record.vehicle().plate()),
                "carId", nvl(record.vehicle().carId()),
                "cargoWeight", nvl(record.vehicle().cargoWeight()),
                "cargoUnit", nvl(record.vehicle().cargoUnit()),
                "status", nvl(record.status()),
                "deleted", Boolean.TRUE.equals(record.deleted())
        ));

        String routeSignature = signature(Map.of(
                "fromKey", fromKey,
                "toKey", toKey,
                "fromCoords", fromCoords == null ? List.of() : List.of(fromCoords[0], fromCoords[1]),
                "toCoords", toCoords == null ? List.of() : List.of(toCoords[0], toCoords[1]),
                "upToDate", Boolean.TRUE.equals(record.upToDate())
        ));

        return new ExternalOrderRoute(
                orderId,
                orderId == null ? record.lineId() : orderId,
                record.lineId(),

                fromKey,
                toKey,

                fromName,
                toName,

                fromCoords,
                toCoords,

                coordinates,

                pathKey,
                segmentKeys,

                record.vehicle().plate(),
                record.vehicle().carId(),

                record.vehicle().cargoWeight(),
                record.vehicle().cargoUnit(),

                record.status(),
                record.updatedAt(),

                speedKmh,
                routeLengthKm,
                travelDurationMs,

                record.vehicle().currentCoords(),

                dataSignature,
                routeSignature
        );
    }

    private boolean isValid(ExternalOrderRecord record) {
        if (record == null) return false;
        if (record.lineId() == null || record.lineId().isBlank()) return false;
        if (record.from() == null) return false;
        if (record.to() == null) return false;
        if (record.vehicle() == null) return false;
        if (record.updatedAt() == null || record.updatedAt().isBlank()) return false;

        String fromKey = locationKey(record.from());
        String toKey = locationKey(record.to());

        return !fromKey.isBlank() && !toKey.isBlank();
    }

    private String locationKey(ExternalOrderRecord.Location location) {
        if (location == null) return "";

        if (location.adcode() != null && !location.adcode().isBlank()) {
            return location.adcode().trim();
        }

        String province = safe(location.province());
        String city = safe(location.city());
        String district = safe(location.district());
        String name = safe(location.name());

        String key = String.join("|",
                province,
                city,
                !district.isBlank() ? district : name
        );

        return key.replaceAll("\\|+$", "");
    }

    private String locationName(ExternalOrderRecord.Location location) {
        if (location == null) return "";

        if (location.name() != null && !location.name().isBlank()) {
            return location.name().trim();
        }

        return safe(location.province()) + safe(location.city()) + safe(location.district());
    }

    private boolean hasCoords(double[] coords) {
        return coords != null && coords.length >= 2;
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private Object nvl(Object value) {
        return value == null ? "" : value;
    }

    private String signature(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return String.valueOf(value);
        }
    }

    private double distanceKm(double[] start, double[] end) {
        if (!hasCoords(start) || !hasCoords(end)) {
            return 0;
        }

        double earthRadiusKm = 6371.0;

        double startLat = Math.toRadians(start[1]);
        double endLat = Math.toRadians(end[1]);
        double deltaLat = Math.toRadians(end[1] - start[1]);
        double deltaLng = Math.toRadians(end[0] - start[0]);

        double a = Math.sin(deltaLat / 2) * Math.sin(deltaLat / 2)
                + Math.cos(startLat) * Math.cos(endLat)
                * Math.sin(deltaLng / 2) * Math.sin(deltaLng / 2);

        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));

        return earthRadiusKm * c;
    }

    private static final class CacheEntry {
        private final ExternalOrderRoute route;
        private final String dataSignature;
        private final String routeSignature;
        private final String updatedAt;
        private long lastSeenBatch;

        private CacheEntry(
                ExternalOrderRoute route,
                String dataSignature,
                String routeSignature,
                String updatedAt,
                long lastSeenBatch
        ) {
            this.route = route;
            this.dataSignature = dataSignature;
            this.routeSignature = routeSignature;
            this.updatedAt = updatedAt;
            this.lastSeenBatch = lastSeenBatch;
        }

        public ExternalOrderRoute route() {
            return route;
        }

        public String dataSignature() {
            return dataSignature;
        }

        public String routeSignature() {
            return routeSignature;
        }

        public String updatedAt() {
            return updatedAt;
        }

        public long lastSeenBatch() {
            return lastSeenBatch;
        }

        public void lastSeenBatch(long lastSeenBatch) {
            this.lastSeenBatch = lastSeenBatch;
        }
    }
}