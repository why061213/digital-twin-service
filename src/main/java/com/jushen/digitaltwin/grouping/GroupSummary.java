package com.jushen.digitaltwin.grouping;

import java.util.List;

/**
 * 分组摘要，用于 REST 接口或后续调试接口返回。
 *
 * <p>groupType 是粗分类，groupScenario 是具体业务场景，displayTemplate 是建议前端
 * 使用的展示模板。这样后端负责判断业务语义，前端负责把语义映射成视觉表达。</p>
 */
public class GroupSummary {
    private final String groupId;
    private final String groupKey;
    private final int index;
    private final int subIndex;
    private final int count;
    private final List<RouteInfo> routes;

    private final String groupType;
    private final String groupScenario;
    private final String scenarioReason;
    private final String displayTemplate;
    private final RouteGroupStyleHint styleHint;
    private final List<String> orderIds;
    private final String routeKey;
    private final String pathKey;
    private final List<String> segmentKeys;

    public GroupSummary(String groupId, String groupKey, int index, int subIndex, int count, List<RouteInfo> routes) {
        this(
                groupId,
                groupKey,
                index,
                subIndex,
                count,
                routes,
                RouteGroupType.MIXED,
                RouteGroupScenario.MIXED,
                RouteGroupScenario.MIXED.getDescription(),
                DisplayTemplate.BASIC,
                RouteGroupStyleHint.of(RouteGroupScenario.MIXED),
                List.of(),
                null,
                null,
                List.of()
        );
    }

    public GroupSummary(
            String groupId,
            String groupKey,
            int index,
            int subIndex,
            int count,
            List<RouteInfo> routes,
            RouteGroupType groupType,
            RouteGroupStyleHint styleHint,
            List<String> orderIds,
            String routeKey,
            String pathKey,
            List<String> segmentKeys
    ) {
        this(
                groupId,
                groupKey,
                index,
                subIndex,
                count,
                routes,
                groupType,
                RouteGroupScenario.MIXED,
                RouteGroupScenario.MIXED.getDescription(),
                DisplayTemplate.BASIC,
                styleHint,
                orderIds,
                routeKey,
                pathKey,
                segmentKeys
        );
    }

    public GroupSummary(
            String groupId,
            String groupKey,
            int index,
            int subIndex,
            int count,
            List<RouteInfo> routes,
            RouteGroupType groupType,
            RouteGroupScenario groupScenario,
            String scenarioReason,
            DisplayTemplate displayTemplate,
            RouteGroupStyleHint styleHint,
            List<String> orderIds,
            String routeKey,
            String pathKey,
            List<String> segmentKeys
    ) {
        RouteGroupType safeType = groupType == null ? RouteGroupType.MIXED : groupType;
        RouteGroupScenario safeScenario = groupScenario == null ? RouteGroupScenario.MIXED : groupScenario;
        DisplayTemplate safeTemplate = displayTemplate == null ? DisplayTemplate.of(safeScenario) : displayTemplate;

        this.groupId = groupId;
        this.groupKey = groupKey;
        this.index = index;
        this.subIndex = subIndex;
        this.count = count;
        this.routes = routes == null ? List.of() : List.copyOf(routes);
        this.groupType = safeType.getCode();
        this.groupScenario = safeScenario.getCode();
        this.scenarioReason = scenarioReason == null || scenarioReason.isBlank()
                ? safeScenario.getDescription()
                : scenarioReason;
        this.displayTemplate = safeTemplate.getCode();
        this.styleHint = styleHint == null ? RouteGroupStyleHint.of(safeScenario) : styleHint;
        this.orderIds = orderIds == null ? List.of() : List.copyOf(orderIds);
        this.routeKey = routeKey;
        this.pathKey = pathKey;
        this.segmentKeys = segmentKeys == null ? List.of() : List.copyOf(segmentKeys);
    }

    public String getGroupId() {
        return groupId;
    }

    public String getGroupKey() {
        return groupKey;
    }

    public int getIndex() {
        return index;
    }

    public int getSubIndex() {
        return subIndex;
    }

    public int getCount() {
        return count;
    }

    public List<RouteInfo> getRoutes() {
        return routes;
    }

    public String getGroupType() {
        return groupType;
    }

    public String getGroupScenario() {
        return groupScenario;
    }

    public String getScenarioReason() {
        return scenarioReason;
    }

    public String getDisplayTemplate() {
        return displayTemplate;
    }

    public RouteGroupStyleHint getStyleHint() {
        return styleHint;
    }

    public List<String> getOrderIds() {
        return orderIds;
    }

    public String getRouteKey() {
        return routeKey;
    }

    public String getPathKey() {
        return pathKey;
    }

    public List<String> getSegmentKeys() {
        return segmentKeys;
    }
}
