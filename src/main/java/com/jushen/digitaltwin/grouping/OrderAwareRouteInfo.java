package com.jushen.digitaltwin.grouping;

/**
 * 可选扩展接口：当路线能明确关联业务订单时，实现该接口即可按订单分组。
 *
 * <p>当前模拟路线如果没有真实订单号，按订单策略会自动退化为 lineId。
 * 后续接入真实订单系统时，只需要让路线 DTO 实现 getOrderId()。</p>
 */
public interface OrderAwareRouteInfo extends RouteInfo {
    String getOrderId();
}
