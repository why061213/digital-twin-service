package com.jushen.digitaltwin.townroad;

import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

@Component
public class ProvinceRoadGraph {

    private final Map<String, Set<String>> graph = new LinkedHashMap<>();

    public ProvinceRoadGraph() {
        buildMainlandAdjacency();
    }

    public List<String> shortestPath(String start, String target) {
        if (isBlank(start) || isBlank(target)) return List.of();
        if (start.equals(target)) return List.of(start);

        Queue<String> queue = new ArrayDeque<>();
        Map<String, String> previous = new HashMap<>();
        Set<String> visited = new LinkedHashSet<>();

        queue.add(start);
        visited.add(start);

        while (!queue.isEmpty()) {
            String current = queue.poll();

            for (String next : graph.getOrDefault(current, Collections.emptySet())) {
                if (visited.contains(next)) continue;

                visited.add(next);
                previous.put(next, current);

                if (next.equals(target)) {
                    return rebuildPath(start, target, previous);
                }

                queue.add(next);
            }
        }

        return List.of();
    }

    public boolean hasProvince(String provinceKey) {
        return graph.containsKey(provinceKey);
    }

    private List<String> rebuildPath(String start, String target, Map<String, String> previous) {
        List<String> path = new ArrayList<>();
        String cursor = target;

        while (cursor != null) {
            path.add(cursor);
            if (cursor.equals(start)) break;
            cursor = previous.get(cursor);
        }

        Collections.reverse(path);

        if (path.isEmpty() || !path.get(0).equals(start)) {
            return List.of();
        }

        return path;
    }

    private void buildMainlandAdjacency() {
        // 华北
        link("110000", "130000"); // 北京-河北
        link("120000", "130000"); // 天津-河北
        link("130000", "140000"); // 河北-山西
        link("130000", "150000"); // 河北-内蒙古
        link("130000", "210000"); // 河北-辽宁
        link("130000", "370000"); // 河北-山东
        link("130000", "410000"); // 河北-河南
        link("140000", "150000"); // 山西-内蒙古（补全）

        // 东北
        link("150000", "210000");
        link("150000", "220000");
        link("150000", "230000");
        link("210000", "220000");
        link("220000", "230000");

        // 华东
        link("310000", "320000"); // 上海-江苏
        link("310000", "330000"); // 上海-浙江
        link("320000", "330000"); // 江苏-浙江
        link("320000", "340000"); // 江苏-安徽
        link("320000", "370000"); // 江苏-山东
        link("330000", "340000"); // 浙江-安徽
        link("330000", "350000"); // 浙江-福建
        link("330000", "360000"); // 浙江-江西
        link("340000", "370000"); // 安徽-山东
        link("340000", "410000"); // 安徽-河南
        link("340000", "420000"); // 安徽-湖北
        link("340000", "360000"); // 安徽-江西
        link("350000", "360000"); // 福建-江西
        link("350000", "440000"); // 福建-广东，注意：广东和福建接壤
        link("350000", "710000"); // 福建-台湾，按物流可渡海处理（补全）
        link("360000", "420000"); // 江西-湖北
        link("360000", "430000"); // 江西-湖南
        link("360000", "440000"); // 江西-广东
        link("370000", "410000"); // 山东-河南

        // 华中 / 华南
        link("410000", "140000"); // 河南-山西
        link("410000", "610000"); // 河南-陕西
        link("410000", "420000"); // 河南-湖北
        link("420000", "430000"); // 湖北-湖南
        link("420000", "500000"); // 湖北-重庆
        link("420000", "610000"); // 湖北-陕西
        link("430000", "440000"); // 湖南-广东
        link("430000", "450000"); // 湖南-广西
        link("430000", "520000"); // 湖南-贵州
        link("430000", "500000"); // 湖南-重庆
        link("440000", "450000"); // 广东-广西
        link("440000", "460000"); // 广东-海南，按物流可渡海处理
        link("440000", "810000"); // 广东-香港（补全）
        link("440000", "820000"); // 广东-澳门（补全）
        link("450000", "520000"); // 广西-贵州
        link("450000", "530000"); // 广西-云南
        link("450000", "460000"); // 广西-海南，按物流可渡海处理

        // 西南
        link("500000", "510000"); // 重庆-四川
        link("500000", "520000"); // 重庆-贵州
        link("500000", "610000"); // 重庆-陕西
        link("510000", "520000"); // 四川-贵州
        link("510000", "530000"); // 四川-云南
        link("510000", "540000"); // 四川-西藏
        link("510000", "610000"); // 四川-陕西
        link("510000", "620000"); // 四川-甘肃
        link("510000", "630000"); // 四川-青海
        link("520000", "530000"); // 贵州-云南
        link("530000", "540000"); // 云南-西藏

        // 西北
        link("610000", "140000"); // 陕西-山西
        link("610000", "150000"); // 陕西-内蒙古
        link("610000", "620000"); // 陕西-甘肃
        link("610000", "640000"); // 陕西-宁夏
        link("620000", "150000"); // 甘肃-内蒙古
        link("620000", "630000"); // 甘肃-青海
        link("620000", "640000"); // 甘肃-宁夏
        link("620000", "650000"); // 甘肃-新疆
        link("630000", "540000"); // 青海-西藏
        link("630000", "650000"); // 青海-新疆
        link("640000", "150000"); // 宁夏-内蒙古
        link("650000", "540000"); // 新疆-西藏
    }

    private void link(String a, String b) {
        graph.computeIfAbsent(a, ignored -> new LinkedHashSet<>()).add(b);
        graph.computeIfAbsent(b, ignored -> new LinkedHashSet<>()).add(a);
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}