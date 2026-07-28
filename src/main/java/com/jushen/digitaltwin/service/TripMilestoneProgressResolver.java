package com.jushen.digitaltwin.service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** 将位置帧中已经由 Trip 状态机确认的动态节点状态覆盖到初始路线快照。 */
final class TripMilestoneProgressResolver {
    private static final Set<String> DYNAMIC_META_KEYS = Set.of(
            "currentLegId", "planVersion", "tripPhase", "tripDecision", "positionQuality",
            "targetStopId", "targetOrderInstanceId", "targetAction", "tripStatusText",
            "tripStops", "pendingOrderCount", "onboardOrderCount", "completedOrderCount"
    );

    private TripMilestoneProgressResolver() {
    }

    static Map<String, Object> mergeDynamicMetadata(
            Map<String, Object> baseline,
            Map<String, Object> position
    ) {
        if (position == null || position.isEmpty()) return baseline;
        Map<String, Object> merged = new LinkedHashMap<>(baseline == null ? Map.of() : baseline);
        for (String key : DYNAMIC_META_KEYS) {
            if (position.containsKey(key)) merged.put(key, position.get(key));
            else if (position.containsKey("tripId") && key.startsWith("target")) merged.remove(key);
        }
        return Map.copyOf(merged);
    }
}
