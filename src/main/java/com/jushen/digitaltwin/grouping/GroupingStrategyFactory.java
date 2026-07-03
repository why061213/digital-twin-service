package com.jushen.digitaltwin.grouping;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class GroupingStrategyFactory {
    private final Map<String, GroupingStrategy> strategyMap;

    public GroupingStrategyFactory(List<GroupingStrategy> strategies) {
        strategyMap = strategies.stream()
                .collect(Collectors.toMap(GroupingStrategy::getName, Function.identity()));
    }

    public GroupingStrategy getStrategy(String name) {
        return strategyMap.getOrDefault(name, new SequentialStrategy());
    }
}