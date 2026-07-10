package com.jushen.digitaltwin.townroad;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record OrderSnapshotDiff(
        int added,
        int updated,
        int deleted,
        int unchanged,
        int routeChanged,
        int skippedInvalid,
        int skippedNotRenderable,
        int skippedLongHaul,
        int deletedOrCancelled,
        List<String> addedLineIds,
        List<String> updatedLineIds,
        List<String> deletedLineIds,
        List<String> unchangedLineIds,
        List<String> routeChangedLineIds,
        List<String> skippedInvalidLineIds,
        List<String> skippedNotRenderableLineIds,
        List<String> skippedLongHaulLineIds,
        List<String> deletedOrCancelledLineIds
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
        result.put("deletedOrCancelled", deletedOrCancelled);

        // 详情：每个分类的具体订单号
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("added", toDetail(added, addedLineIds));
        details.put("updated", toDetail(updated, updatedLineIds));
        details.put("deleted", toDetail(deleted, deletedLineIds));
        details.put("unchanged", toDetail(unchanged, unchangedLineIds));
        details.put("routeChanged", toDetail(routeChanged, routeChangedLineIds));
        details.put("skippedInvalid", toDetail(skippedInvalid, skippedInvalidLineIds));
        details.put("skippedNotRenderable", toDetail(skippedNotRenderable, skippedNotRenderableLineIds));
        details.put("skippedLongHaul", toDetail(skippedLongHaul, skippedLongHaulLineIds));
        details.put("deletedOrCancelled", toDetail(deletedOrCancelled, deletedOrCancelledLineIds));
        result.put("details", details);

        return result;
    }

    private static Map<String, Object> toDetail(int count, List<String> lineIds) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("count", count);
        detail.put("lineIds", lineIds != null ? lineIds : List.of());
        return detail;
    }
}