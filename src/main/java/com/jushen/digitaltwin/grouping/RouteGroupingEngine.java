package com.jushen.digitaltwin.grouping;

import java.util.Collections;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 路线分组策略引擎。
 *
 * <p>该类是后续 RoutePushService 的推荐接入点。
 * 它负责策略解析、分组执行、根据 groupId 反查路线。</p>
 */
@Component
public class RouteGroupingEngine {
    private final RouteGroupingRegistry registry;

    public RouteGroupingEngine(RouteGroupingRegistry registry) {
        this.registry = registry;
    }

    public RouteGroupingResult group(List<? extends RouteInfo> routes, GroupingContext context) {
        AdvancedGroupingStrategy strategy = registry.resolve(context.getStrategyName());
        List<GroupSummary> groups = strategy.group(routes, context);
        return new RouteGroupingResult(
                strategy.name(),
                context.getGroupSize(),
                routes.size(),
                groups
        );
    }

    public List<RouteInfo> routesByGroup(
            List<? extends RouteInfo> routes,
            GroupingContext context,
            String groupId
    ) {
        if (groupId == null || groupId.isBlank()) {
            return Collections.emptyList();
        }

        return group(routes, context).getGroups().stream()
                .filter((group) -> groupId.equals(group.getGroupId()))
                .findFirst()
                .map(GroupSummary::getRoutes)
                .orElse(Collections.emptyList());
    }

    public List<String> strategyNames() {
        return registry.names();
    }
}
