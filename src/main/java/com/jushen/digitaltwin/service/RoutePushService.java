package com.jushen.digitaltwin.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jushen.digitaltwin.model.City;
import com.jushen.digitaltwin.websocket.RealtimeWebSocketHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

@Service
public class RoutePushService {

    private static final Logger log = LoggerFactory.getLogger(RoutePushService.class);

    private final RealtimeWebSocketHandler webSocketHandler;
    private final SimulationDataFactory dataFactory;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final Map<String, ScheduledRoute> activeRoutes = new ConcurrentHashMap<>();
    private final Map<String, PositionSample> lastPositionSamples = new ConcurrentHashMap<>();
    private final boolean passivePositionPushEnabled;
    private final String simulationProfile;
    private final String externalPositionUrl;
    private final double testSimulationSpeedKmh;
    private final double realSimulationSpeedKmh;
    private final int groupSize;

    public RoutePushService(
            RealtimeWebSocketHandler webSocketHandler,
            SimulationDataFactory dataFactory,
            ObjectMapper objectMapper,
            @Value("${dashboard.route.passive-position-push-enabled:false}") boolean passivePositionPushEnabled,
            @Value("${dashboard.route.simulation-profile:test}") String simulationProfile,
            @Value("${dashboard.route.external-position-url:}") String externalPositionUrl,
            @Value("${dashboard.route.test.simulation-speed-kmh:36000}") double testSimulationSpeedKmh,
            @Value("${dashboard.route.real.simulation-speed-kmh:80}") double realSimulationSpeedKmh,
            @Value("${dashboard.route.group-size:5}") int groupSize
    ) {
        this.webSocketHandler = webSocketHandler;
        this.dataFactory = dataFactory;
        this.objectMapper = objectMapper;
        this.passivePositionPushEnabled = passivePositionPushEnabled;
        this.simulationProfile = simulationProfile;
        this.externalPositionUrl = externalPositionUrl;
        this.testSimulationSpeedKmh = testSimulationSpeedKmh;
        this.realSimulationSpeedKmh = realSimulationSpeedKmh;
        this.groupSize = Math.max(1, groupSize);
    }

    public synchronized Map<String, Object> dispatchRandomRoute() {
        cleanupExpiredRoutes(System.currentTimeMillis());

        City from = dataFactory.randomCity();
        City to = dataFactory.randomDifferentCity(from);
        String lineId = UUID.randomUUID().toString();
        List<double[]> coordinates = buildRandomRoadCoordinates(from, to);
        double routeLengthKm = pathLengthKm(coordinates);
        double speedKmh = simulationSpeedKmh();
        long travelDurationMs = Math.max(60_000L, Math.round(routeLengthKm / speedKmh * 3_600_000));
        ScheduledRoute route = new ScheduledRoute(
                lineId,
                from.name(),
                to.name(),
                coordinates,
                System.currentTimeMillis(),
                routeLengthKm,
                speedKmh,
                travelDurationMs
        );
        activeRoutes.put(lineId, route);

        Map<String, Object> message = routeMessage(route, true);
        webSocketHandler.broadcast(message);
        log.debug("Dispatched road route: {} -> {}, lineId: {}", from.name(), to.name(), lineId);
        return message;
    }

    public Map<String, Object> listRouteGroups() {
        cleanupExpiredRoutes(System.currentTimeMillis());
        List<ScheduledRoute> routes = sortedActiveRoutes();
        List<Map<String, Object>> groups = new ArrayList<>();
        for (int start = 0; start < routes.size(); start += groupSize) {
            int index = start / groupSize;
            int end = Math.min(routes.size(), start + groupSize);
            Map<String, Object> group = new LinkedHashMap<>();
            group.put("groupId", groupId(index));
            group.put("index", index);
            group.put("count", end - start);
            groups.add(group);
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("groupSize", groupSize);
        response.put("totalRoutes", routes.size());
        response.put("groups", groups);
        return response;
    }

    public Map<String, Object> listRoutesByGroup(String groupId) {
        cleanupExpiredRoutes(System.currentTimeMillis());
        int groupIndex = parseGroupIndex(groupId);
        List<ScheduledRoute> routes = sortedActiveRoutes();
        int start = groupIndex * groupSize;
        int end = Math.min(routes.size(), start + groupSize);
        List<Map<String, Object>> routeMessages = new ArrayList<>();
        if (start < routes.size()) {
            routes.subList(start, end).forEach((route) -> routeMessages.add(routeMessage(route, false)));
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("groupId", groupId(groupIndex));
        response.put("groupSize", groupSize);
        response.put("routes", routeMessages);
        return response;
    }

    public Map<String, Object> getPosition(String lineId) {
        long now = System.currentTimeMillis();
        cleanupExpiredRoutes(now);
        ScheduledRoute route = activeRoutes.get(lineId);
        if (route == null) {
            return Map.of(
                    "type", "truck_position",
                    "lineId", lineId,
                    "status", "finished"
            );
        }

        return positionMessage(route, now);
    }

    @Scheduled(fixedRateString = "${dashboard.route.truck-position-push-rate-ms:60000}")
    public void pushPassiveTruckPositions() {
        if (!passivePositionPushEnabled) return;

        long now = System.currentTimeMillis();
        cleanupExpiredRoutes(now);
        activeRoutes.values().forEach((route) -> webSocketHandler.broadcast(positionMessage(route, now)));
    }

    private List<double[]> buildRandomRoadCoordinates(City from, City to) {
        List<double[]> waypoints = new ArrayList<>();
        waypoints.add(new double[]{from.lng(), from.lat()});

        int extraPoints = ThreadLocalRandom.current().nextInt(1, 3);
        for (int i = 0; i < extraPoints; i++) {
            double ratio = (i + 1.0) / (extraPoints + 1.0);
            double baseLng = from.lng() + (to.lng() - from.lng()) * ratio;
            double baseLat = from.lat() + (to.lat() - from.lat()) * ratio;
            double offsetLng = ThreadLocalRandom.current().nextDouble(-2.2, 2.2);
            double offsetLat = ThreadLocalRandom.current().nextDouble(-1.8, 1.8);
            waypoints.add(new double[]{baseLng + offsetLng, baseLat + offsetLat});
        }

        waypoints.add(new double[]{to.lng(), to.lat()});
        return dataFactory.simulateMultiPointPath(waypoints, 200);
    }

    private Map<String, Object> routeMessage(ScheduledRoute route, boolean created) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("type", "road_path");
        message.put("lineId", route.lineId());
        message.put("groupId", groupIdFor(route.lineId()));
        message.put("from", route.from());
        message.put("to", route.to());
        message.put("coordinates", route.coordinates());
        message.put("created", created);
        message.put("routeLengthKm", route.routeLengthKm());
        message.put("speedKmh", route.speedKmh());
        message.put("travelDurationMs", route.travelDurationMs());
        return message;
    }

    private List<ScheduledRoute> sortedActiveRoutes() {
        return activeRoutes.values().stream()
                .sorted(Comparator.comparingLong(ScheduledRoute::startTime))
                .toList();
    }

    private String groupIdFor(String lineId) {
        List<ScheduledRoute> routes = sortedActiveRoutes();
        for (int i = 0; i < routes.size(); i++) {
            if (routes.get(i).lineId().equals(lineId)) {
                return groupId(i / groupSize);
            }
        }
        return groupId(0);
    }

    private int parseGroupIndex(String groupId) {
        if (groupId == null || groupId.isBlank()) {
            return 0;
        }
        if (groupId.startsWith("group-")) {
            try {
                return Math.max(0, Integer.parseInt(groupId.substring("group-".length())) - 1);
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        try {
            return Math.max(0, Integer.parseInt(groupId));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private String groupId(int index) {
        return "group-" + (Math.max(0, index) + 1);
    }

    private Map<String, Object> positionMessage(ScheduledRoute route, long now) {
        ProviderPosition providerPosition = fetchVehiclePosition(route, now);
        double speedKmh = providerPosition.speedKmh();
        if (speedKmh <= 0) {
            speedKmh = calculateSpeedKmh(route.lineId(), providerPosition.position(), now, route.speedKmh());
        }

        long elapsed = Math.max(0, now - route.startTime());
        double progress = Math.min(1.0, elapsed / (double) route.travelDurationMs());
        double[] velocity = velocityFromSpeed(route.coordinates(), progress, speedKmh);

        Map<String, Object> message = new LinkedHashMap<>();
        message.put("type", "truck_position");
        message.put("lineId", route.lineId());
        message.put("position", providerPosition.position());
        message.put("velocity", velocity);
        message.put("speedKmh", speedKmh);
        message.put("progress", progress);
        message.put("status", progress >= 1.0 ? "finished" : "running");
        return message;
    }

    private void cleanupExpiredRoutes(long now) {
        activeRoutes.entrySet().removeIf((entry) -> {
            boolean expired = now - entry.getValue().startTime() > entry.getValue().travelDurationMs();
            if (expired) {
                lastPositionSamples.remove(entry.getKey());
            }
            return expired;
        });
    }

    private ProviderPosition fetchVehiclePosition(ScheduledRoute route, long now) {
        ProviderPosition externalPosition = fetchExternalVehiclePosition(route.lineId());
        if (externalPosition != null) {
            return externalPosition;
        }

        long elapsed = Math.max(0, now - route.startTime());
        double progress = Math.min(1.0, elapsed / (double) route.travelDurationMs());
        double[] position = coordinateAtProgress(route.coordinates(), progress);
        return new ProviderPosition(position, 0);
    }

    private ProviderPosition fetchExternalVehiclePosition(String lineId) {
        if (externalPositionUrl == null || externalPositionUrl.isBlank()) {
            return null;
        }

        String url = externalPositionUrl.replace("{lineId}", lineId);
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url)).GET().build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log.warn("External vehicle position request failed: status={}, lineId={}", response.statusCode(), lineId);
                return null;
            }

            Map<String, Object> body = objectMapper.readValue(response.body(), new TypeReference<>() {});
            double[] position = readPosition(body.get("position"));
            if (position == null) {
                return null;
            }

            double speedKmh = readDouble(body.get("speedKmh"));
            if (speedKmh <= 0) {
                speedKmh = readDouble(body.get("velocityKmh"));
            }
            if (speedKmh <= 0) {
                speedKmh = readDouble(body.get("speed"));
            }
            return new ProviderPosition(position, speedKmh);
        } catch (Exception error) {
            log.warn("External vehicle position request failed: lineId={}", lineId, error);
            return null;
        }
    }

    private double[] readPosition(Object value) {
        if (!(value instanceof List<?> list) || list.size() < 2) {
            return null;
        }
        Object lng = list.get(0);
        Object lat = list.get(1);
        if (!(lng instanceof Number lngNumber) || !(lat instanceof Number latNumber)) {
            return null;
        }
        return new double[]{lngNumber.doubleValue(), latNumber.doubleValue()};
    }

    private double readDouble(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        return 0;
    }

    private double calculateSpeedKmh(String lineId, double[] position, long now, double fallbackSpeedKmh) {
        PositionSample previous = lastPositionSamples.put(lineId, new PositionSample(position, now));
        if (previous == null || now <= previous.time()) {
            return fallbackSpeedKmh;
        }

        double elapsedHours = (now - previous.time()) / 3_600_000.0;
        if (elapsedHours <= 0) {
            return fallbackSpeedKmh;
        }

        double distanceKm = distanceKm(previous.position(), position);
        double speedKmh = distanceKm / elapsedHours;
        return speedKmh > 0 ? speedKmh : fallbackSpeedKmh;
    }

    private double[] velocityFromSpeed(List<double[]> coordinates, double progress, double speedKmh) {
        double[] position = coordinateAtProgress(coordinates, progress);
        double[] nextPosition = coordinateAtProgress(coordinates, Math.min(1, progress + 0.001));
        double lngDelta = nextPosition[0] - position[0];
        double latDelta = nextPosition[1] - position[1];
        double vectorLength = Math.sqrt(lngDelta * lngDelta + latDelta * latDelta);
        if (vectorLength <= 0 || speedKmh <= 0) {
            return new double[]{0, 0};
        }

        double kmPerMs = speedKmh / 3_600_000.0;
        double kmForVector = distanceKm(position, nextPosition);
        if (kmForVector <= 0) {
            return new double[]{0, 0};
        }

        double degreesPerMsScale = kmPerMs / kmForVector;
        return new double[]{
                lngDelta * degreesPerMsScale,
                latDelta * degreesPerMsScale
        };
    }

    private double[] coordinateAtProgress(List<double[]> coordinates, double progress) {
        if (coordinates == null || coordinates.isEmpty()) {
            return new double[]{0, 0};
        }
        if (coordinates.size() == 1) {
            return coordinates.get(0);
        }

        double totalLength = pathLength(coordinates);
        if (totalLength <= 0) {
            return coordinates.get(0);
        }

        double targetDistance = Math.max(0, Math.min(1, progress)) * totalLength;
        double walked = 0;
        for (int i = 1; i < coordinates.size(); i++) {
            double[] start = coordinates.get(i - 1);
            double[] end = coordinates.get(i);
            double segmentLength = distance(start, end);
            if (segmentLength <= 0) {
                continue;
            }

            if (walked + segmentLength >= targetDistance) {
                double localProgress = (targetDistance - walked) / segmentLength;
                return new double[]{
                        start[0] + (end[0] - start[0]) * localProgress,
                        start[1] + (end[1] - start[1]) * localProgress
                };
            }
            walked += segmentLength;
        }

        return coordinates.get(coordinates.size() - 1);
    }

    private double pathLength(List<double[]> coordinates) {
        double total = 0;
        for (int i = 1; i < coordinates.size(); i++) {
            total += distance(coordinates.get(i - 1), coordinates.get(i));
        }
        return total;
    }

    private double distance(double[] start, double[] end) {
        double dx = end[0] - start[0];
        double dy = end[1] - start[1];
        return Math.sqrt(dx * dx + dy * dy);
    }

    private double pathLengthKm(List<double[]> coordinates) {
        double total = 0;
        for (int i = 1; i < coordinates.size(); i++) {
            total += distanceKm(coordinates.get(i - 1), coordinates.get(i));
        }
        return total;
    }

    private double distanceKm(double[] start, double[] end) {
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

    private double simulationSpeedKmh() {
        if ("real".equalsIgnoreCase(simulationProfile)) {
            return Math.max(1, realSimulationSpeedKmh);
        }
        return Math.max(1, testSimulationSpeedKmh);
    }

    private record ScheduledRoute(
            String lineId,
            String from,
            String to,
            List<double[]> coordinates,
            long startTime,
            double routeLengthKm,
            double speedKmh,
            long travelDurationMs
    ) {
    }

    private record ProviderPosition(
            double[] position,
            double speedKmh
    ) {
    }

    private record PositionSample(
            double[] position,
            long time
    ) {
    }
}
