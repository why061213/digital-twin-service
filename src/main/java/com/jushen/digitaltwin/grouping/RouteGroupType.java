package com.jushen.digitaltwin.grouping;

/**
 * 分组语义类型。
 *
 * <p>前端后续可以直接根据这些值选择不同视觉样式，但当前隔离模块只负责给出语义，
 * 不绑定具体颜色、线型或动画。</p>
 */
public enum RouteGroupType {
    SAME_ORDER("same_order"),
    SAME_ROUTE("same_route"),
    SAME_PATH("same_path"),
    TIME_WINDOW("time_window"),
    SEQUENTIAL("sequential"),
    MIXED("mixed");

    private final String code;

    RouteGroupType(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
