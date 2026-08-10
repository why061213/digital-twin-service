package com.jushen.digitaltwin.townroad;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Uses the local province polygons as a union to keep generated road segments on Chinese land. */
@Component
public class ChinaBoundaryConstraint {

    private static final Logger log = LoggerFactory.getLogger(ChinaBoundaryConstraint.class);
    private static final double MAX_SEGMENT_SAMPLE_KM = 5.0;
    private static final double MAX_REPAIR_DETOUR_RATIO = 2.8;
    private static final double BOUNDARY_CANDIDATE_DETOUR_RATIO = 2.2;
    private static final int MAX_BOUNDARY_CANDIDATES = 2_000;
    private static final int MAX_REPAIR_DEPTH = 10;
    private static final int MAX_CONTAINS_DEPTH = 14;

    private final List<PolygonArea> landPolygons;

    public ChinaBoundaryConstraint(ObjectMapper objectMapper) {
        this.landPolygons = loadPolygons(objectMapper);
        log.info("[ChinaBoundary] loaded local land polygons: count={}", landPolygons.size());
    }

    public List<double[]> constrainRoute(List<double[]> route) {
        if (route == null || route.size() < 2 || landPolygons.isEmpty()) {
            return route == null ? List.of() : route;
        }
        List<double[]> points = dedupe(route);
        if (points.size() < 2) return points;

        double[] start = points.get(0);
        double[] end = points.get(points.size() - 1);
        double directKm = distanceKm(start, end);
        double originalKm = pathLengthKm(points);
        if (segmentInside(start, end)) {
            return List.of(copy(start), copy(end));
        }

        List<double[]> candidates = new ArrayList<>();
        for (double[] point : points) {
            if (contains(point)) addDistinctCandidate(candidates, point);
        }
        for (double[] point : nearbyBoundaryCandidates(start, end)) {
            addDistinctCandidate(candidates, point);
        }
        List<double[]> repaired = repairSegment(
                start,
                end,
                candidates,
                new HashSet<>(),
                0
        );
        if (repaired.size() >= 2
                && allSegmentsInside(repaired)
                && pathLengthKm(repaired) <= originalKm) {
            log.info("[ChinaBoundary] route repaired: originalPoints={}, repairedPoints={}, originalKm={}, repairedKm={}",
                    points.size(), repaired.size(), Math.round(originalKm), Math.round(pathLengthKm(repaired)));
            return repaired;
        }

        log.warn("[ChinaBoundary] unable to safely shorten constrained route: points={}, directKm={}, routeKm={}",
                points.size(), Math.round(directKm), Math.round(originalKm));
        return points;
    }

    private List<double[]> nearbyBoundaryCandidates(double[] from, double[] to) {
        double directKm = Math.max(1, distanceKm(from, to));
        List<BoundaryCandidate> ranked = new ArrayList<>();
        for (PolygonArea polygon : landPolygons) {
            if (!polygon.nearRoute(from, to, directKm)) continue;
            for (double[] point : polygon.outer()) {
                double detourKm = distanceKm(from, point) + distanceKm(point, to);
                if (detourKm <= directKm * BOUNDARY_CANDIDATE_DETOUR_RATIO) {
                    ranked.add(new BoundaryCandidate(point, detourKm));
                }
            }
        }
        ranked.sort(java.util.Comparator.comparingDouble(BoundaryCandidate::detourKm));
        List<double[]> result = new ArrayList<>();
        for (BoundaryCandidate candidate : ranked) {
            addDistinctCandidate(result, candidate.point());
            if (result.size() >= MAX_BOUNDARY_CANDIDATES) break;
        }
        return result;
    }

    private void addDistinctCandidate(List<double[]> candidates, double[] point) {
        String key = coordinateKey(point);
        for (double[] existing : candidates) {
            if (coordinateKey(existing).equals(key)) return;
        }
        candidates.add(copy(point));
    }

    boolean segmentInside(double[] from, double[] to) {
        if (!valid(from) || !valid(to)) return false;
        return segmentInside(from, to, 0);
    }

    private boolean segmentInside(double[] from, double[] to, int depth) {
        double[] midpoint = midpoint(from, to);
        if (!contains(midpoint)) return false;
        if (depth >= MAX_CONTAINS_DEPTH || distanceKm(from, to) <= MAX_SEGMENT_SAMPLE_KM) {
            return true;
        }
        return segmentInside(from, midpoint, depth + 1)
                && segmentInside(midpoint, to, depth + 1);
    }

    private List<double[]> repairSegment(
            double[] from,
            double[] to,
            List<double[]> candidates,
            Set<String> used,
            int depth
    ) {
        if (segmentInside(from, to)) return List.of(copy(from), copy(to));
        if (depth >= MAX_REPAIR_DEPTH) return List.of();

        int currentOutside = outsideSampleCount(from, to);
        double directKm = Math.max(1, distanceKm(from, to));
        double[] best = null;
        int bestOutside = Integer.MAX_VALUE;
        double bestDistance = Double.POSITIVE_INFINITY;
        for (double[] candidate : candidates) {
            String key = coordinateKey(candidate);
            if (used.contains(key) || samePoint(candidate, from) || samePoint(candidate, to)) continue;
            double detourKm = distanceKm(from, candidate) + distanceKm(candidate, to);
            if (detourKm > directKm * MAX_REPAIR_DETOUR_RATIO) continue;

            int outside = outsideSampleCount(from, candidate) + outsideSampleCount(candidate, to);
            boolean improvesBoundary = outside < currentOutside;
            boolean createsSafeHalf = segmentInside(from, candidate) || segmentInside(candidate, to);
            if (!improvesBoundary && !createsSafeHalf) continue;
            if (outside < bestOutside || (outside == bestOutside && detourKm < bestDistance)) {
                best = candidate;
                bestOutside = outside;
                bestDistance = detourKm;
            }
        }
        if (best == null) return List.of();

        Set<String> nextUsed = new HashSet<>(used);
        nextUsed.add(coordinateKey(best));
        List<double[]> left = repairSegment(from, best, candidates, nextUsed, depth + 1);
        List<double[]> right = repairSegment(best, to, candidates, nextUsed, depth + 1);
        if (left.isEmpty() || right.isEmpty()) return List.of();

        List<double[]> result = new ArrayList<>(left);
        result.remove(result.size() - 1);
        result.addAll(right);
        return result;
    }

    private int outsideSampleCount(double[] from, double[] to) {
        int samples = Math.max(2, Math.min(256, (int) Math.ceil(distanceKm(from, to) / 10.0)));
        int outside = 0;
        for (int i = 1; i < samples; i++) {
            double ratio = i / (double) samples;
            double[] point = {
                    from[0] + (to[0] - from[0]) * ratio,
                    from[1] + (to[1] - from[1]) * ratio
            };
            if (!contains(point)) outside++;
        }
        return outside;
    }

    private boolean allSegmentsInside(List<double[]> points) {
        for (int i = 1; i < points.size(); i++) {
            if (!segmentInside(points.get(i - 1), points.get(i))) return false;
        }
        return true;
    }

    private boolean contains(double[] point) {
        if (!valid(point)) return false;
        for (PolygonArea polygon : landPolygons) {
            if (polygon.contains(point)) return true;
        }
        return false;
    }

    private List<PolygonArea> loadPolygons(ObjectMapper objectMapper) {
        List<PolygonArea> polygons = new ArrayList<>();
        try (InputStream input = new ClassPathResource("map/china.json").getInputStream()) {
            JsonNode root = objectMapper.readTree(input);
            for (JsonNode feature : root.path("features")) {
                JsonNode adcode = feature.path("properties").path("adcode");
                if (!adcode.isIntegralNumber()) continue;
                JsonNode geometry = feature.path("geometry");
                String type = geometry.path("type").asText();
                JsonNode coordinates = geometry.path("coordinates");
                if ("Polygon".equals(type)) {
                    addPolygon(polygons, coordinates);
                } else if ("MultiPolygon".equals(type)) {
                    for (JsonNode polygon : coordinates) addPolygon(polygons, polygon);
                }
            }
        } catch (Exception error) {
            log.error("[ChinaBoundary] failed to load local boundary data", error);
        }
        return List.copyOf(polygons);
    }

    private void addPolygon(List<PolygonArea> polygons, JsonNode ringsNode) {
        if (!ringsNode.isArray() || ringsNode.isEmpty()) return;
        List<double[]> outer = readRing(ringsNode.get(0));
        if (outer.size() < 3) return;
        List<List<double[]>> holes = new ArrayList<>();
        for (int i = 1; i < ringsNode.size(); i++) {
            List<double[]> hole = readRing(ringsNode.get(i));
            if (hole.size() >= 3) holes.add(hole);
        }
        polygons.add(new PolygonArea(outer, holes));
    }

    private List<double[]> readRing(JsonNode ringNode) {
        List<double[]> ring = new ArrayList<>();
        if (!ringNode.isArray()) return ring;
        for (JsonNode coordinate : ringNode) {
            if (!coordinate.isArray() || coordinate.size() < 2) continue;
            double[] point = {coordinate.get(0).asDouble(), coordinate.get(1).asDouble()};
            if (valid(point)) ring.add(point);
        }
        return ring;
    }

    private List<double[]> dedupe(List<double[]> route) {
        List<double[]> result = new ArrayList<>();
        for (double[] point : route) {
            if (!valid(point)) continue;
            if (result.isEmpty() || !samePoint(result.get(result.size() - 1), point)) result.add(copy(point));
        }
        return result;
    }

    private double pathLengthKm(List<double[]> points) {
        double total = 0;
        for (int i = 1; i < points.size(); i++) total += distanceKm(points.get(i - 1), points.get(i));
        return total;
    }

    private double distanceKm(double[] from, double[] to) {
        double lat1 = Math.toRadians(from[1]);
        double lat2 = Math.toRadians(to[1]);
        double deltaLat = lat2 - lat1;
        double deltaLng = Math.toRadians(to[0] - from[0]);
        double a = Math.sin(deltaLat / 2) * Math.sin(deltaLat / 2)
                + Math.cos(lat1) * Math.cos(lat2) * Math.sin(deltaLng / 2) * Math.sin(deltaLng / 2);
        return 6_371.0088 * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    private double[] midpoint(double[] from, double[] to) {
        return new double[]{(from[0] + to[0]) / 2, (from[1] + to[1]) / 2};
    }

    private boolean samePoint(double[] left, double[] right) {
        return Math.abs(left[0] - right[0]) < 0.000001 && Math.abs(left[1] - right[1]) < 0.000001;
    }

    private String coordinateKey(double[] point) {
        return String.format(java.util.Locale.ROOT, "%.6f,%.6f", point[0], point[1]);
    }

    private double[] copy(double[] point) {
        return new double[]{point[0], point[1]};
    }

    private boolean valid(double[] point) {
        return point != null && point.length >= 2
                && Double.isFinite(point[0]) && Double.isFinite(point[1])
                && point[0] >= 70 && point[0] <= 140 && point[1] >= 0 && point[1] <= 60;
    }

    private record PolygonArea(
            List<double[]> outer,
            List<List<double[]>> holes,
            double minLng,
            double maxLng,
            double minLat,
            double maxLat
    ) {
        private PolygonArea(List<double[]> outer, List<List<double[]>> holes) {
            this(
                    List.copyOf(outer), List.copyOf(holes),
                    outer.stream().mapToDouble(point -> point[0]).min().orElse(0),
                    outer.stream().mapToDouble(point -> point[0]).max().orElse(0),
                    outer.stream().mapToDouble(point -> point[1]).min().orElse(0),
                    outer.stream().mapToDouble(point -> point[1]).max().orElse(0)
            );
        }

        private boolean contains(double[] point) {
            if (point[0] < minLng || point[0] > maxLng || point[1] < minLat || point[1] > maxLat) return false;
            if (!pointInRing(point, outer)) return false;
            for (List<double[]> hole : holes) {
                if (pointInRing(point, hole)) return false;
            }
            return true;
        }

        private boolean nearRoute(double[] from, double[] to, double directKm) {
            double latitudePadding = directKm / 110.574;
            double meanLatitude = Math.toRadians((from[1] + to[1]) / 2.0);
            double longitudePadding = directKm / Math.max(30.0, 111.320 * Math.cos(meanLatitude));
            double routeMinLng = Math.min(from[0], to[0]) - longitudePadding;
            double routeMaxLng = Math.max(from[0], to[0]) + longitudePadding;
            double routeMinLat = Math.min(from[1], to[1]) - latitudePadding;
            double routeMaxLat = Math.max(from[1], to[1]) + latitudePadding;
            return maxLng >= routeMinLng && minLng <= routeMaxLng
                    && maxLat >= routeMinLat && minLat <= routeMaxLat;
        }

        private static boolean pointInRing(double[] point, List<double[]> ring) {
            boolean inside = false;
            for (int i = 0, j = ring.size() - 1; i < ring.size(); j = i++) {
                double[] a = ring.get(j);
                double[] b = ring.get(i);
                if (pointOnSegment(point, a, b)) return true;
                boolean intersects = (a[1] > point[1]) != (b[1] > point[1])
                        && point[0] < (b[0] - a[0]) * (point[1] - a[1]) / (b[1] - a[1]) + a[0];
                if (intersects) inside = !inside;
            }
            return inside;
        }

        private static boolean pointOnSegment(double[] point, double[] from, double[] to) {
            double cross = (point[0] - from[0]) * (to[1] - from[1])
                    - (point[1] - from[1]) * (to[0] - from[0]);
            if (Math.abs(cross) > 0.000001) return false;
            return point[0] >= Math.min(from[0], to[0]) - 0.000001
                    && point[0] <= Math.max(from[0], to[0]) + 0.000001
                    && point[1] >= Math.min(from[1], to[1]) - 0.000001
                    && point[1] <= Math.max(from[1], to[1]) + 0.000001;
        }
    }

    private record BoundaryCandidate(double[] point, double detourKm) {
    }
}
