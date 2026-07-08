package com.jushen.digitaltwin.townroad;

import java.util.List;

public record ExternalOrderSnapshotResult(
        int rawCount,
        int normalizedCount,
        int shortHaulCount,
        List<TownRoadRenderCommand> commands,
        OrderSnapshotDiff diff
) {
}