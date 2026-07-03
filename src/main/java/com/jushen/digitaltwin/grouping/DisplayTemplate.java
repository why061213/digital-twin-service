package com.jushen.digitaltwin.grouping;

/**
 * 建议前端采用的展示模板。
 *
 * <p>模板只表达“展示结构”，不表达具体颜色或 CSS。比如订单进度模板可以展示整体进度，
 * 路径压力模板可以展示多订单共用道路的情况。</p>
 */
public enum DisplayTemplate {
    ROUTE_FLOW("route_flow"),
    ORDER_PROGRESS("order_progress"),
    PATH_PRESSURE("path_pressure"),
    ROUTE_CLUSTER("route_cluster"),
    TIME_BATCH("time_batch"),
    BASIC("basic");

    private final String code;

    DisplayTemplate(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }

    public static DisplayTemplate of(RouteGroupScenario scenario) {
        return switch (scenario) {
            case SINGLE_ORDER_SINGLE_PATH -> ROUTE_FLOW;
            case SINGLE_ORDER_MULTI_PATH -> ORDER_PROGRESS;
            case MULTI_ORDER_SAME_PATH -> PATH_PRESSURE;
            case MULTI_ORDER_SAME_ROUTE, SAME_ROUTE_MULTI_PATH -> ROUTE_CLUSTER;
            case TIME_BATCH -> TIME_BATCH;
            case SEQUENTIAL, MIXED -> BASIC;
        };
    }
}
