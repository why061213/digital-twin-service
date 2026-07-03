package com.jushen.digitaltwin.grouping;

import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 按订单分组。
 *
 * <p>如果 RouteInfo 实现 OrderAwareRouteInfo，则使用 getOrderId()。
 * 当前模拟数据没有真实订单号时，会自动退化为 lineId。</p>
 */
@Component
public class OrderGroupingStrategy implements AdvancedGroupingStrategy {

    @Override
    public String name() {
        return "by-order";
    }

    @Override
    public String description() {
        return "按 orderId 聚合路线；无 orderId 时使用 lineId。";
    }

    @Override
    public List<GroupSummary> group(List<? extends RouteInfo> routes, GroupingContext context) {
        Map<String, List<RouteInfo>> buckets = GroupingUtils.groupBy(
                routes,
                (route) -> "订单 " + GroupingUtils.orderKey(route)
        );
        return GroupingUtils.splitBuckets(buckets, context.getGroupSize(), "order", RouteGroupType.SAME_ORDER);
    }
}
