package com.jushen.digitaltwin.grouping;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * 分组策略注册表。
 *
 * <p>Spring 启动时自动收集所有 AdvancedGroupingStrategy 实现。
 * 新增策略时只要实现接口并添加 @Component，无需修改注册表。</p>
 */
@Component
public class RouteGroupingRegistry {
    private final Map<String, AdvancedGroupingStrategy> strategies;
    private final AdvancedGroupingStrategy fallbackStrategy;

    public RouteGroupingRegistry(List<AdvancedGroupingStrategy> strategies) {
        if (strategies == null || strategies.isEmpty()) {
            throw new IllegalStateException("至少需要注册一个路线分组策略");
        }
        this.strategies = strategies.stream()
                .sorted(Comparator.comparing(AdvancedGroupingStrategy::name))
                .collect(Collectors.toMap(
                        AdvancedGroupingStrategy::name,
                        Function.identity(),
                        (left, ignored) -> left
                ));
        this.fallbackStrategy = this.strategies.getOrDefault(
                "business-priority",
                this.strategies.getOrDefault("sequential", strategies.get(0))
        );
    }

    public AdvancedGroupingStrategy resolve(String name) {
        if (name == null || name.isBlank()) {
            return fallbackStrategy;
        }
        return strategies.getOrDefault(name, fallbackStrategy);
    }

    public List<String> names() {
        return strategies.keySet().stream().sorted().toList();
    }
}
