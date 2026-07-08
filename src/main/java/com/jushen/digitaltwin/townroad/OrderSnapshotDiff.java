package com.jushen.digitaltwin.townroad;

import java.util.LinkedHashMap;
import java.util.Map;

public record OrderSnapshotDiff(
        int added,
        int updated,
        int deleted,
        int unchanged,
        int routeChanged,
        int skippedInvalid,
        int skippedNotRenderable,
        int skippedLongHaul
) {
    public Map<String, Object> toMap() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("added", added);
        result.put("updated", updated);
        result.put("deleted", deleted);
        result.put("unchanged", unchanged);
        result.put("routeChanged", routeChanged);
        result.put("skippedInvalid", skippedInvalid);
        result.put("skippedNotRenderable", skippedNotRenderable);
        result.put("skippedLongHaul", skippedLongHaul);
        return result;
    }
}