package com.jushen.digitaltwin.externalorder;

import java.util.List;

public record ExternalOrderDiff(
        List<ExternalOrderRoute> added,
        List<ExternalOrderRoute> updated,
        List<ExternalOrderRoute> deleted,
        List<ExternalOrderRoute> unchanged,
        List<ExternalOrderRoute> routeChanged
) {
    public int addedCount() {
        return added.size();
    }

    public int updatedCount() {
        return updated.size();
    }

    public int deletedCount() {
        return deleted.size();
    }

    public int unchangedCount() {
        return unchanged.size();
    }

    public int routeChangedCount() {
        return routeChanged.size();
    }
}