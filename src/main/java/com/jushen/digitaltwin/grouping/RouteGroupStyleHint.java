package com.jushen.digitaltwin.grouping;

/**
 * 分组展示提示。
 *
 * <p>这里输出的是“展示语义”，不是具体 style。前端可以把 variant 映射到自己的
 * 线条、节点、标签、动画模板。</p>
 */
public class RouteGroupStyleHint {
    private final String category;
    private final int priority;
    private final String variant;

    public RouteGroupStyleHint(String category, int priority, String variant) {
        this.category = category;
        this.priority = priority;
        this.variant = variant;
    }

    public static RouteGroupStyleHint of(RouteGroupType groupType) {
        return switch (groupType) {
            case SAME_ORDER -> new RouteGroupStyleHint("order", 100, "same-order");
            case SAME_ROUTE -> new RouteGroupStyleHint("route", 80, "same-route");
            case SAME_PATH -> new RouteGroupStyleHint("path", 70, "same-path");
            case TIME_WINDOW -> new RouteGroupStyleHint("time", 50, "time-window");
            case SEQUENTIAL -> new RouteGroupStyleHint("sequence", 20, "sequential");
            case MIXED -> new RouteGroupStyleHint("mixed", 10, "mixed");
        };
    }

    public static RouteGroupStyleHint of(RouteGroupScenario scenario) {
        return switch (scenario) {
            case SINGLE_ORDER_SINGLE_PATH -> new RouteGroupStyleHint("route", 60, scenario.getCode());
            case SINGLE_ORDER_MULTI_PATH -> new RouteGroupStyleHint("order", 100, scenario.getCode());
            case MULTI_ORDER_SAME_PATH -> new RouteGroupStyleHint("path", 90, scenario.getCode());
            case MULTI_ORDER_SAME_ROUTE -> new RouteGroupStyleHint("route", 80, scenario.getCode());
            case SAME_ROUTE_MULTI_PATH -> new RouteGroupStyleHint("route", 75, scenario.getCode());
            case TIME_BATCH -> new RouteGroupStyleHint("time", 50, scenario.getCode());
            case SEQUENTIAL -> new RouteGroupStyleHint("sequence", 20, scenario.getCode());
            case MIXED -> new RouteGroupStyleHint("mixed", 10, scenario.getCode());
        };
    }

    public String getCategory() {
        return category;
    }

    public int getPriority() {
        return priority;
    }

    public String getVariant() {
        return variant;
    }
}
