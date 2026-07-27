package com.jushen.digitaltwin.routeanalysis;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

import static com.jushen.digitaltwin.routeanalysis.RouteAnalysisDTO.RoutePartDTO;
import static com.jushen.digitaltwin.routeanalysis.RouteAnalysisDTO.SharedRouteRefDTO;

/**
 * Computes route roles and shared corridors in metres. Rendering sample counts never
 * participate in these decisions.
 */
@Service
public class RouteAnalysisService {
    public static final String ANALYSIS_VERSION = "route-analysis-v2";
    private static final double EARTH_RADIUS_M = 6_371_000.0;

    private final double resampleStepM;
    private final double sharedToleranceM;
    private final double sharedMinLengthM;
    private final double sharedDirectionDot;
    private final double deviationEnterDistanceM;
    private final double deviationExitDistanceM;
    private final double deviationMinLengthM;
    private final double gridSizeM;
    private final ConcurrentMap<String, Map<String, RouteAnalysisDTO>> cache = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Long> branchGroupOrdinal = new ConcurrentHashMap<>();
    private final AtomicLong nextBranchGroupOrdinal = new AtomicLong();

    public RouteAnalysisService(
            @Value("${dashboard.route-analysis.resample-step-m:100}") double resampleStepM,
            @Value("${dashboard.route-analysis.shared-tolerance-m:120}") double sharedToleranceM,
            @Value("${dashboard.route-analysis.shared-min-length-m:800}") double sharedMinLengthM,
            @Value("${dashboard.route-analysis.shared-direction-dot:0.97}") double sharedDirectionDot,
            @Value("${dashboard.route-analysis.deviation-enter-distance-m:300}") double deviationEnterDistanceM,
            @Value("${dashboard.route-analysis.deviation-exit-distance-m:150}") double deviationExitDistanceM,
            @Value("${dashboard.route-analysis.deviation-min-length-m:300}") double deviationMinLengthM,
            @Value("${dashboard.route-analysis.grid-size-m:500}") double gridSizeM
    ) {
        this.resampleStepM = positive(resampleStepM, 100);
        this.sharedToleranceM = positive(sharedToleranceM, 120);
        this.sharedMinLengthM = positive(sharedMinLengthM, 800);
        this.sharedDirectionDot = Math.max(0, Math.min(1, sharedDirectionDot));
        this.deviationEnterDistanceM = positive(deviationEnterDistanceM, 300);
        this.deviationExitDistanceM = positive(deviationExitDistanceM, 150);
        this.deviationMinLengthM = positive(deviationMinLengthM, 300);
        this.gridSizeM = positive(gridSizeM, 500);
    }

    public Map<String, RouteAnalysisDTO> analyze(List<Map<String, Object>> effectiveRoutes) {
        String cacheKey = cacheKey(effectiveRoutes);
        Map<String, RouteAnalysisDTO> cached = cache.get(cacheKey);
        if (cached != null) return cached;
        double referenceLat = groupReferenceLatitude(effectiveRoutes);
        List<Route> routes = effectiveRoutes == null ? List.of() : effectiveRoutes.stream()
                .map(value -> toRoute(value, referenceLat))
                .filter(route -> route != null && route.samples.size() >= 2)
                .toList();
        Map<String, RouteAnalysisDTO> result = new LinkedHashMap<>();
        if (routes.isEmpty()) return result;

        routes.forEach(this::assignInitialBranchGroups);
        Map<GridCell, List<Segment>> index = buildIndex(routes);
        for (int left = 0; left < routes.size(); left++) {
            for (int right = left + 1; right < routes.size(); right++) {
                Route routeA = routes.get(left);
                Route routeB = routes.get(right);
                if (routeA.businessLineId.equals(routeB.businessLineId)) {
                    markSharedRuns(routeA, routeB, index);
                }
            }
        }
        routes.forEach(route -> result.put(route.lineId, assemble(route, routes)));
        Map<String, RouteAnalysisDTO> immutable = Map.copyOf(result);
        if (cache.size() >= 256) cache.clear();
        cache.putIfAbsent(cacheKey, immutable);
        return cache.get(cacheKey);
    }

    private Route toRoute(Map<String, Object> value, double referenceLat) {
        String lineId = text(value.get("lineId"));
        List<double[]> coordinates = coordinates(value.get("coordinates"));
        if (lineId == null || coordinates.size() < 2) return null;
        List<double[]> baseline = coordinates(value.get("baselineCoordinates"));
        List<Sample> samples = resample(coordinates, referenceLat);
        boolean[] deviation = deviationStates(samples, baseline.isEmpty() ? coordinates : baseline);
        return new Route(
                lineId,
                businessLineId(value, lineId),
                text(value.get("orderId")),
                text(value.get("plate")),
                visualKey(value),
                samples,
                deviation,
                sharedSets(samples.size() - 1),
                sharedSets(samples.size() - 1)
        );
    }

    @SuppressWarnings("unchecked")
    private String visualKey(Map<String, Object> route) {
        Object meta = route.get("meta");
        if (meta instanceof Map<?, ?> map) return text(map.get("visualKey"));
        return null;
    }

    private Map<GridCell, List<Segment>> buildIndex(List<Route> routes) {
        Map<GridCell, List<Segment>> index = new HashMap<>();
        for (Route route : routes) {
            for (int segmentIndex = 0; segmentIndex < route.samples.size() - 1; segmentIndex++) {
                Segment segment = new Segment(route, segmentIndex);
                for (GridCell cell : cells(segment, sharedToleranceM)) {
                    index.computeIfAbsent(cell, ignored -> new ArrayList<>()).add(segment);
                }
            }
        }
        return index;
    }

    private void markSharedRuns(Route routeA, Route routeB, Map<GridCell, List<Segment>> index) {
        List<Match> matches = new ArrayList<>();
        for (int indexA = 0; indexA < routeA.samples.size() - 1; indexA++) {
            if (!routeA.deviation[indexA]) continue;
            Segment a = new Segment(routeA, indexA);
            Match best = null;
            Set<SegmentKey> seen = new HashSet<>();
            for (GridCell cell : cells(a, sharedToleranceM)) {
                for (Segment b : index.getOrDefault(cell, List.of())) {
                    if (b.route != routeB || !routeB.deviation[b.index]
                            || !seen.add(new SegmentKey(b.route.lineId, b.index))) continue;
                    Match candidate = match(a, b);
                    if (candidate != null && (best == null || candidate.distance < best.distance)) best = candidate;
                }
            }
            if (best != null) matches.add(best);
        }
        if (matches.isEmpty()) return;

        int runStart = 0;
        for (int cursor = 1; cursor <= matches.size(); cursor++) {
            boolean continuous = cursor < matches.size() && continuous(matches.get(cursor - 1), matches.get(cursor));
            if (continuous) continue;
            applySharedRun(routeA, routeB, matches.subList(runStart, cursor));
            runStart = cursor;
        }
    }

    private boolean continuous(Match previous, Match current) {
        if (current.a.index != previous.a.index + 1 || current.directionSign != previous.directionSign) return false;
        int deltaB = current.b.index - previous.b.index;
        return current.directionSign > 0 ? deltaB >= 0 && deltaB <= 2 : deltaB <= 0 && deltaB >= -2;
    }

    private void applySharedRun(Route routeA, Route routeB, List<Match> run) {
        if (run.isEmpty()) return;
        double length = routeA.samples.get(run.get(run.size() - 1).a.index + 1).measure
                - routeA.samples.get(run.get(0).a.index).measure;
        if (length + 0.001 < sharedMinLengthM) return;
        String branchGroupId = existingBranchGroup(run);
        if (branchGroupId == null) {
            branchGroupId = branchGroupId(
                    routeA, run.get(0).a.index, run.get(run.size() - 1).a.index);
            rememberBranchGroup(branchGroupId);
        }
        for (Match match : run) {
            routeA.sharedBySegment.get(match.a.index).add(routeB.lineId);
            routeB.sharedBySegment.get(match.b.index).add(routeA.lineId);
            assignBranchGroupToDeviationRun(routeA, match.a.index, branchGroupId);
            assignBranchGroupToDeviationRun(routeB, match.b.index, branchGroupId);
        }
    }

    private void assignInitialBranchGroups(Route route) {
        int start = 0;
        while (start < route.deviation.length - 1) {
            if (!route.deviation[start]) {
                start++;
                continue;
            }
            int end = start;
            while (end + 1 < route.deviation.length - 1 && route.deviation[end + 1]) end++;
            String groupId = branchGroupId(route, start, end);
            rememberBranchGroup(groupId);
            for (int index = start; index <= end; index++) {
                route.branchGroupsBySegment.get(index).add(groupId);
            }
            start = end + 1;
        }
    }

    private void assignBranchGroupToDeviationRun(Route route, int segmentIndex, String groupId) {
        int start = segmentIndex;
        int end = segmentIndex;
        while (start > 0 && route.deviation[start - 1]) start--;
        while (end + 1 < route.deviation.length - 1 && route.deviation[end + 1]) end++;
        for (int index = start; index <= end; index++) {
            Set<String> groups = route.branchGroupsBySegment.get(index);
            groups.clear();
            groups.add(groupId);
        }
    }

    private void rememberBranchGroup(String groupId) {
        branchGroupOrdinal.computeIfAbsent(groupId, ignored -> nextBranchGroupOrdinal.getAndIncrement());
    }

    private Match match(Segment a, Segment b) {
        double dot = a.directionX * b.directionX + a.directionY * b.directionY;
        if (Math.abs(dot) < sharedDirectionDot) return null;
        double d1 = pointToSegmentDistance(a.start, b.start, b.end);
        double d2 = pointToSegmentDistance(a.end, b.start, b.end);
        double d3 = pointToSegmentDistance(b.start, a.start, a.end);
        double d4 = pointToSegmentDistance(b.end, a.start, a.end);
        double maxDistance = Math.max(Math.max(d1, d2), Math.max(d3, d4));
        return maxDistance <= sharedToleranceM ? new Match(a, b, dot >= 0 ? 1 : -1, maxDistance) : null;
    }

    private RouteAnalysisDTO assemble(Route route, List<Route> allRoutes) {
        Map<String, Route> byId = new HashMap<>();
        allRoutes.forEach(candidate -> byId.put(candidate.lineId, candidate));
        List<RoutePartDTO> parts = new ArrayList<>();
        int start = 0;
        for (int cursor = 1; cursor <= route.sharedBySegment.size(); cursor++) {
            boolean same = cursor < route.sharedBySegment.size()
                    && role(route, cursor) == role(route, start)
                    && route.sharedBySegment.get(cursor).equals(route.sharedBySegment.get(start))
                    && route.branchGroupsBySegment.get(cursor).equals(route.branchGroupsBySegment.get(start));
            if (same) continue;
            int end = cursor;
            Set<String> participantIds = route.sharedBySegment.get(start);
            List<SharedRouteRefDTO> participants = participantIds.stream()
                    .map(byId::get)
                    .filter(candidate -> candidate != null)
                    .sorted(Comparator.comparing(candidate -> candidate.lineId))
                    .map(candidate -> new SharedRouteRefDTO(
                            candidate.lineId, candidate.visualKey, candidate.orderId, candidate.plate))
                    .toList();
            List<double[]> partCoordinates = new ArrayList<>();
            for (int sampleIndex = start; sampleIndex <= end; sampleIndex++) {
                Sample sample = route.samples.get(sampleIndex);
                partCoordinates.add(new double[]{sample.lng, sample.lat});
            }
            double from = route.samples.get(start).measure;
            double to = route.samples.get(end).measure;
            String branchGroupId = route.branchGroupsBySegment.get(start).stream().sorted().findFirst().orElse(null);
            parts.add(new RoutePartDTO(
                    "part-" + (parts.size() + 1), from, to, role(route, start),
                    List.copyOf(partCoordinates), null, branchGroupId, participants));
            start = cursor;
        }
        double total = route.samples.get(route.samples.size() - 1).measure;
        return new RouteAnalysisDTO(ANALYSIS_VERSION, total, List.copyOf(parts));
    }

    private String role(Route route, int segmentIndex) {
        return route.deviation[Math.min(segmentIndex, route.deviation.length - 1)] ? "DEVIATION" : "NORMAL";
    }

    private boolean[] deviationStates(List<Sample> samples, List<double[]> baseline) {
        boolean[] states = new boolean[samples.size()];
        List<Sample> baselineSamples = projectOnly(baseline, samples.get(0).referenceLat);
        boolean deviating = false;
        double deviationStartedAt = 0;
        for (int index = 0; index < samples.size(); index++) {
            Sample sample = samples.get(index);
            double distance = distanceToPath(sample, baselineSamples);
            if (!deviating && distance > deviationEnterDistanceM) {
                deviating = true;
                deviationStartedAt = sample.measure;
            } else if (deviating && distance < deviationExitDistanceM
                    && sample.measure - deviationStartedAt >= deviationMinLengthM) {
                deviating = false;
            }
            states[index] = deviating;
        }
        return states;
    }

    private List<Sample> resample(List<double[]> coordinates, double referenceLat) {
        List<Sample> projected = projectOnly(coordinates, referenceLat);
        List<Sample> result = new ArrayList<>();
        result.add(withMeasure(projected.get(0), 0));
        double total = 0;
        double nextMeasure = resampleStepM;
        for (int index = 1; index < projected.size(); index++) {
            Sample start = projected.get(index - 1);
            Sample end = projected.get(index);
            double segmentLength = distance(start, end);
            if (segmentLength < 0.01) continue;
            double segmentStart = total;
            total += segmentLength;
            while (nextMeasure < total) {
                double ratio = (nextMeasure - segmentStart) / segmentLength;
                result.add(new Sample(
                        start.lng + (end.lng - start.lng) * ratio,
                        start.lat + (end.lat - start.lat) * ratio,
                        start.x + (end.x - start.x) * ratio,
                        start.y + (end.y - start.y) * ratio,
                        nextMeasure,
                        referenceLat));
                nextMeasure += resampleStepM;
            }
        }
        Sample last = projected.get(projected.size() - 1);
        if (distance(result.get(result.size() - 1), last) > 0.01) result.add(withMeasure(last, total));
        return result;
    }

    private List<Sample> projectOnly(List<double[]> coordinates, double referenceLat) {
        List<Sample> result = new ArrayList<>();
        double cosine = Math.cos(Math.toRadians(referenceLat));
        for (double[] point : coordinates) {
            result.add(new Sample(
                    point[0], point[1],
                    Math.toRadians(point[0]) * EARTH_RADIUS_M * cosine,
                    Math.toRadians(point[1]) * EARTH_RADIUS_M,
                    0,
                    referenceLat));
        }
        return result;
    }

    private List<GridCell> cells(Segment segment, double padding) {
        int minX = (int) Math.floor((Math.min(segment.start.x, segment.end.x) - padding) / gridSizeM);
        int maxX = (int) Math.floor((Math.max(segment.start.x, segment.end.x) + padding) / gridSizeM);
        int minY = (int) Math.floor((Math.min(segment.start.y, segment.end.y) - padding) / gridSizeM);
        int maxY = (int) Math.floor((Math.max(segment.start.y, segment.end.y) + padding) / gridSizeM);
        List<GridCell> result = new ArrayList<>();
        for (int x = minX; x <= maxX; x++) for (int y = minY; y <= maxY; y++) result.add(new GridCell(x, y));
        return result;
    }

    private double distanceToPath(Sample point, List<Sample> path) {
        double nearest = Double.POSITIVE_INFINITY;
        for (int index = 1; index < path.size(); index++) {
            nearest = Math.min(nearest, pointToSegmentDistance(point, path.get(index - 1), path.get(index)));
        }
        return nearest;
    }

    private static double pointToSegmentDistance(Sample point, Sample start, Sample end) {
        double dx = end.x - start.x;
        double dy = end.y - start.y;
        double lengthSquared = dx * dx + dy * dy;
        double ratio = lengthSquared <= 0 ? 0
                : ((point.x - start.x) * dx + (point.y - start.y) * dy) / lengthSquared;
        ratio = Math.max(0, Math.min(1, ratio));
        return Math.hypot(point.x - (start.x + dx * ratio), point.y - (start.y + dy * ratio));
    }

    private static double distance(Sample a, Sample b) {
        return Math.hypot(a.x - b.x, a.y - b.y);
    }

    private static Sample withMeasure(Sample sample, double measure) {
        return new Sample(sample.lng, sample.lat, sample.x, sample.y, measure, sample.referenceLat);
    }

    private static List<Set<String>> sharedSets(int count) {
        List<Set<String>> result = new ArrayList<>(Math.max(0, count));
        for (int index = 0; index < count; index++) result.add(new LinkedHashSet<>());
        return result;
    }

    private static String branchGroupId(Route route, int startIndex, int endIndex) {
        Sample start = route.samples.get(startIndex);
        Sample end = route.samples.get(endIndex + 1);
        String first = quantizedCoordinate(start);
        String last = quantizedCoordinate(end);
        String value = route.businessLineId + '|' + (first.compareTo(last) <= 0
                ? first + '|' + last : last + '|' + first);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return "branch-" + HexFormat.of().formatHex(digest).substring(0, 12);
        } catch (NoSuchAlgorithmException ignored) {
            return "branch-" + Integer.toHexString(value.hashCode());
        }
    }

    private String existingBranchGroup(List<Match> run) {
        String earliest = null;
        for (Match match : run) {
            Set<String> left = match.a.route.branchGroupsBySegment.get(match.a.index);
            Set<String> right = match.b.route.branchGroupsBySegment.get(match.b.index);
            for (String candidate : left) earliest = earlierBranchGroup(earliest, candidate);
            for (String candidate : right) earliest = earlierBranchGroup(earliest, candidate);
        }
        return earliest;
    }

    private String earlierBranchGroup(String current, String candidate) {
        if (current == null) return candidate;
        long currentOrdinal = branchGroupOrdinal.getOrDefault(current, Long.MAX_VALUE);
        long candidateOrdinal = branchGroupOrdinal.getOrDefault(candidate, Long.MAX_VALUE);
        return candidateOrdinal < currentOrdinal ? candidate : current;
    }

    private static String quantizedCoordinate(Sample sample) {
        return Math.round(sample.lng / 0.005) + "," + Math.round(sample.lat / 0.005);
    }

    private static String businessLineId(Map<String, Object> route, String lineId) {
        String businessLineId = text(route.get("businessLineId"));
        if (businessLineId != null) return businessLineId;
        String orderId = text(route.get("orderId"));
        return orderId == null ? lineId : orderId;
    }

    @SuppressWarnings("unchecked")
    private static List<double[]> coordinates(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        List<double[]> result = new ArrayList<>();
        for (Object point : list) {
            if (point instanceof double[] array && valid(array)) {
                result.add(new double[]{array[0], array[1]});
            } else if (point instanceof List<?> values && values.size() >= 2
                    && values.get(0) instanceof Number lng && values.get(1) instanceof Number lat) {
                result.add(new double[]{lng.doubleValue(), lat.doubleValue()});
            }
        }
        return result;
    }

    private static boolean valid(double[] point) {
        return point.length >= 2 && Double.isFinite(point[0]) && Double.isFinite(point[1]);
    }

    private static String text(Object value) {
        return value instanceof String string && !string.isBlank() ? string : null;
    }

    private static double positive(double value, double fallback) {
        return Double.isFinite(value) && value > 0 ? value : fallback;
    }

    private static double groupReferenceLatitude(List<Map<String, Object>> routes) {
        if (routes == null) return 0;
        return routes.stream()
                .flatMap(route -> coordinates(route.get("coordinates")).stream())
                .mapToDouble(point -> point[1])
                .average()
                .orElse(0);
    }

    private String cacheKey(List<Map<String, Object>> routes) {
        if (routes == null || routes.isEmpty()) return "empty";
        List<String> identities = routes.stream()
                .map(route -> String.valueOf(route.get("lineId")) + ':'
                        + String.valueOf(route.get("businessLineId")) + ':'
                        + String.valueOf(route.get("orderId")) + ':'
                        + routeSignature(route) + ':'
                        + String.valueOf(route.get("routeRevision")))
                .sorted()
                .toList();
        return ANALYSIS_VERSION + ':' + resampleStepM + ':' + sharedToleranceM + ':'
                + sharedMinLengthM + ':' + sharedDirectionDot + ':' + deviationEnterDistanceM + ':'
                + deviationExitDistanceM + ':' + deviationMinLengthM + ':' + String.join("|", identities);
    }

    private static String routeSignature(Map<String, Object> route) {
        Object signature = route.get("routeSignature");
        if (signature instanceof String text && !text.isBlank()) return text;
        StringBuilder value = new StringBuilder();
        coordinates(route.get("coordinates")).forEach(point -> value
                .append(Math.round(point[0] * 100_000)).append(',')
                .append(Math.round(point[1] * 100_000)).append(';'));
        return Integer.toHexString(value.toString().hashCode());
    }

    private record Sample(double lng, double lat, double x, double y, double measure, double referenceLat) {}
    private record GridCell(int x, int y) {}
    private record SegmentKey(String lineId, int index) {}
    private record Match(Segment a, Segment b, int directionSign, double distance) {}

    private static final class Segment {
        private final Route route;
        private final int index;
        private final Sample start;
        private final Sample end;
        private final double directionX;
        private final double directionY;

        private Segment(Route route, int index) {
            this.route = route;
            this.index = index;
            this.start = route.samples.get(index);
            this.end = route.samples.get(index + 1);
            double length = Math.max(0.001, distance(start, end));
            this.directionX = (end.x - start.x) / length;
            this.directionY = (end.y - start.y) / length;
        }
    }

    private record Route(
            String lineId,
            String businessLineId,
            String orderId,
            String plate,
            String visualKey,
            List<Sample> samples,
            boolean[] deviation,
            List<Set<String>> sharedBySegment,
            List<Set<String>> branchGroupsBySegment
    ) {}
}
