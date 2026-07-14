package com.jushen.digitaltwin.grouping;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.stereotype.Component;

/**
 * RoadMap 默认展示策略：按始发省、目的省和完整路径签名稳定分页。
 */
@Component
public class ProvincePathGroupingStrategy implements AdvancedGroupingStrategy {
    private static final int ROUTE_DISPLAY_MAX_COUNT = 12;

    @Override
    public String name() {
        return "province-path";
    }

    @Override
    public String description() {
        return "按始发省→目的省→路径哈希稳定分组，每页最多 12 条路线。";
    }

    @Override
    public List<GroupSummary> group(List<? extends RouteInfo> routes, GroupingContext context) {
        int pageSize = Math.min(ROUTE_DISPLAY_MAX_COUNT, context.getGroupSize());
        Map<String, List<RouteInfo>> buckets = new TreeMap<>();
        for (RouteInfo route : routes) {
            String startProvince = provinceOf(route, true);
            String endProvince = provinceOf(route, false);
            String pathHash = GroupingUtils.stableHash(GroupingUtils.pathKey(route));
            String bucketKey = startProvince + ">" + endProvince + ">" + pathHash;
            buckets.computeIfAbsent(bucketKey, ignored -> new ArrayList<>()).add(route);
        }

        List<GroupSummary> groups = new ArrayList<>();
        int groupIndex = 0;
        for (Map.Entry<String, List<RouteInfo>> entry : buckets.entrySet()) {
            List<RouteInfo> bucketRoutes = entry.getValue().stream()
                    .sorted(Comparator.comparing(RouteInfo::getLineId))
                    .toList();
            String[] parts = entry.getKey().split(">", 3);
            String startProvince = parts[0];
            String endProvince = parts[1];
            String pathHash = parts[2];
            String groupKey = startProvince + "→" + endProvince + "→" + pathHash;

            for (int start = 0, page = 1; start < bucketRoutes.size(); start += pageSize, page++) {
                int end = Math.min(bucketRoutes.size(), start + pageSize);
                String groupId = "province-path-"
                        + GroupingUtils.safeGroupToken(startProvince)
                        + "-to-" + GroupingUtils.safeGroupToken(endProvince)
                        + "-" + pathHash
                        + "-page-" + page;
                groups.add(GroupingUtils.buildSummary(
                        groupId,
                        groupKey,
                        groupIndex++,
                        page,
                        new ArrayList<>(bucketRoutes.subList(start, end)),
                        RouteGroupType.SAME_PATH
                ));
            }
        }
        return groups;
    }

    private String provinceOf(RouteInfo route, boolean start) {
        if (route instanceof ProvinceAwareRouteInfo provinceAware) {
            String province = start ? provinceAware.getStartProvince() : provinceAware.getEndProvince();
            if (province != null && !province.isBlank()) {
                return province.trim();
            }
        }
        return GroupingUtils.safeText(start ? route.getFrom() : route.getTo(), "未知省份");
    }
}
