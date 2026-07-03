package com.jushen.digitaltwin.grouping;

/**
 * 路线分组的具体业务场景。
 *
 * <p>这个字段不是视觉样式，而是前后端协作的“场景协议”。
 * 前端可以根据它选择展示模板、交互方式和信息层级。</p>
 */
public enum RouteGroupScenario {
    SINGLE_ORDER_SINGLE_PATH("single_order_single_path", "单订单单路径"),
    SINGLE_ORDER_MULTI_PATH("single_order_multi_path", "单订单多路径"),
    MULTI_ORDER_SAME_PATH("multi_order_same_path", "多订单同路径"),
    MULTI_ORDER_SAME_ROUTE("multi_order_same_route", "多订单同起终点"),
    SAME_ROUTE_MULTI_PATH("same_route_multi_path", "同起终点多路径"),
    TIME_BATCH("time_batch", "同一时间批次"),
    SEQUENTIAL("sequential", "顺序切分"),
    MIXED("mixed", "混合场景");

    private final String code;
    private final String description;

    RouteGroupScenario(String code, String description) {
        this.code = code;
        this.description = description;
    }

    public String getCode() {
        return code;
    }

    public String getDescription() {
        return description;
    }
}
