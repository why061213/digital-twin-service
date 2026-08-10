package com.jushen.digitaltwin.townroad;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class ProvinceRoadGraph {

    private final Map<String, List<WeightedEdge>> graph = new LinkedHashMap<>();

    public ProvinceRoadGraph() {
        buildMainlandAdjacency();
    }

    public List<String> shortestPath(String start, String target) {
        List<ProvincePath> paths = candidatePaths(start, target, 20, 0, 0, 1);
        return paths.isEmpty() ? List.of() : paths.get(0).provinces();
    }

    public List<ProvincePath> candidatePaths(
            String start,
            String target,
            int maxProvinceCount,
            double toleranceRatio,
            int absoluteSlack,
            int maxPathCount
    ) {
        if (isBlank(start) || isBlank(target)) return List.of();
        if (start.equals(target)) return List.of(new ProvincePath(List.of(start), 0));
        if (!graph.containsKey(start) || !graph.containsKey(target)) return List.of();

        int safeMaxProvinceCount = Math.max(2, maxProvinceCount);
        int safeMaxPathCount = Math.max(1, maxPathCount);
        List<ProvincePath> allPaths = new ArrayList<>();
        ArrayList<String> path = new ArrayList<>();
        Set<String> visited = new LinkedHashSet<>();

        path.add(start);
        visited.add(start);
        collectCandidatePaths(start, target, safeMaxProvinceCount, 0, path, visited, allPaths);

        if (allPaths.isEmpty()) return List.of();

        allPaths.sort(Comparator
                .comparingInt(ProvincePath::cost)
                .thenComparing(ProvincePath::pathKey));

        int bestCost = allPaths.get(0).cost();
        int allowedByRatio = (int) Math.ceil(bestCost * (1 + Math.max(0, toleranceRatio)));
        int allowedBySlack = bestCost + Math.max(0, absoluteSlack);
        int allowedCost = Math.min(allowedByRatio, allowedBySlack);

        return allPaths.stream()
                .filter(pathItem -> pathItem.cost() <= allowedCost)
                .limit(safeMaxPathCount)
                .toList();
    }

    public boolean hasProvince(String provinceKey) {
        return graph.containsKey(provinceKey);
    }

    /**
     * 短途分类只看起终点是否位于同省或两个直接相邻的省份。
     * 实际候选路径仍可经过第三省，不用渲染路径长度反推业务分类。
     */
    public boolean isSameOrAdjacent(String start, String target) {
        if (isBlank(start) || isBlank(target)) return false;
        if (start.equals(target)) return true;
        return graph.getOrDefault(start, Collections.emptyList()).stream()
                .anyMatch(edge -> target.equals(edge.to()));
    }

    public List<String> edgeKeys(List<String> provincePath) {
        if (provincePath == null || provincePath.size() < 2) return List.of();

        List<String> result = new ArrayList<>();
        for (int i = 0; i < provincePath.size() - 1; i++) {
            result.add(edgeKey(provincePath.get(i), provincePath.get(i + 1)));
        }
        return result;
    }

    public String edgeKey(String fromProvinceKey, String toProvinceKey) {
        return fromProvinceKey + "->" + toProvinceKey;
    }

    public String pathKey(List<String> provincePath) {
        if (provincePath == null || provincePath.isEmpty()) return "";
        return String.join(">", provincePath);
    }

    private void collectCandidatePaths(
            String current,
            String target,
            int maxProvinceCount,
            int currentCost,
            ArrayList<String> path,
            Set<String> visited,
            List<ProvincePath> result
    ) {
        if (current.equals(target)) {
            result.add(new ProvincePath(List.copyOf(path), currentCost));
            return;
        }

        if (path.size() >= maxProvinceCount) return;

        List<WeightedEdge> neighbors = new ArrayList<>(graph.getOrDefault(current, Collections.emptyList()));
        neighbors.sort(Comparator.comparing(WeightedEdge::to));

        for (WeightedEdge edge : neighbors) {
            String next = edge.to();
            if (visited.contains(next)) continue;

            path.add(next);
            visited.add(next);
            collectCandidatePaths(next, target, maxProvinceCount, currentCost + edge.cost(), path, visited, result);
            visited.remove(next);
            path.remove(path.size() - 1);
        }
    }

    private void buildMainlandAdjacency() {
        link("110000", "130000", 280);
        link("120000", "130000", 220);
        link("130000", "140000", 420);
        link("130000", "150000", 520);
        link("130000", "210000", 650);
        link("130000", "370000", 430);
        link("130000", "410000", 430);

        link("150000", "210000", 650);
        link("150000", "220000", 760);
        link("150000", "230000", 900);
        link("210000", "220000", 350);
        link("220000", "230000", 380);

        link("310000", "320000", 180);
        link("310000", "330000", 220);
        link("320000", "330000", 320);
        link("320000", "340000", 350);
        link("320000", "370000", 520);
        link("330000", "340000", 420);
        link("330000", "350000", 520);
        link("330000", "360000", 520);
        link("340000", "370000", 520);
        link("340000", "410000", 500);
        link("340000", "420000", 520);
        link("340000", "360000", 430);
        link("350000", "360000", 450);
        link("350000", "440000", 600);
        link("360000", "420000", 520);
        link("360000", "430000", 420);
        link("360000", "440000", 500);
        link("370000", "410000", 450);

        link("410000", "140000", 430);
        link("410000", "610000", 520);
        link("410000", "420000", 430);
        link("420000", "430000", 360);
        link("420000", "500000", 650);
        link("420000", "610000", 700);
        link("430000", "440000", 550);
        link("430000", "450000", 520);
        link("430000", "520000", 600);
        link("430000", "500000", 520);
        link("440000", "450000", 450);
        link("440000", "460000", 600);
        link("450000", "520000", 500);
        link("450000", "530000", 650);
        link("450000", "460000", 650);

        link("500000", "510000", 300);
        link("500000", "520000", 300);
        link("500000", "610000", 520);
        link("510000", "520000", 700);
        link("510000", "530000", 650);
        link("510000", "540000", 900);
        link("510000", "610000", 520);
        link("510000", "620000", 760);
        link("510000", "630000", 900);
        link("520000", "530000", 520);
        link("530000", "540000", 900);

        link("610000", "140000", 430);
        link("610000", "150000", 760);
        link("610000", "620000", 520);
        link("610000", "640000", 450);
        link("620000", "150000", 900);
        link("620000", "630000", 520);
        link("620000", "640000", 430);
        link("620000", "650000", 1200);
        link("630000", "540000", 900);
        link("630000", "650000", 1200);
        link("640000", "150000", 900);
        link("650000", "540000", 1400);
    }

    private void link(String a, String b, int cost) {
        graph.computeIfAbsent(a, ignored -> new ArrayList<>()).add(new WeightedEdge(b, cost));
        graph.computeIfAbsent(b, ignored -> new ArrayList<>()).add(new WeightedEdge(a, cost));
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private record WeightedEdge(String to, int cost) {
    }

    public record ProvincePath(List<String> provinces, int cost) {
        public String pathKey() {
            return String.join(">", provinces);
        }

        public List<String> edgeKeys() {
            List<String> result = new ArrayList<>();
            for (int i = 0; i < provinces.size() - 1; i++) {
                result.add(provinces.get(i) + "->" + provinces.get(i + 1));
            }
            return result;
        }
    }
}
