package com.jushen.digitaltwin.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class PositionSnapshotTest {

    @Test
    void predictsFromOriginalAnchorUsingSpeedAndHeading() {
        Instant anchor = Instant.parse("2026-07-17T08:00:00Z");
        PositionSnapshot snapshot = snapshot(anchor, 60, 90, false);

        PositionSnapshot afterOneHour = snapshot.predictAt(anchor.plusSeconds(3_600));

        assertEquals(0, afterOneHour.lat(), 0.001);
        assertEquals(0.5396, afterOneHour.lng(), 0.002);
        assertEquals(anchor, afterOneHour.fetchedAt());
        assertEquals(0, snapshot.lng(), 0.0);
    }

    @Test
    void repeatedReadsDoNotCompoundPrediction() {
        Instant anchor = Instant.parse("2026-07-17T08:00:00Z");
        PositionSnapshot snapshot = snapshot(anchor, 60, 0, false);
        Instant predictionTime = anchor.plusSeconds(1_800);

        PositionSnapshot first = snapshot.predictAt(predictionTime);
        PositionSnapshot second = snapshot.predictAt(predictionTime);

        assertEquals(first.lng(), second.lng(), 0.0);
        assertEquals(first.lat(), second.lat(), 0.0);
        assertTrue(first.lat() > 0);
    }

    @Test
    void staleOrStationarySnapshotDoesNotMove() {
        Instant anchor = Instant.parse("2026-07-17T08:00:00Z");
        PositionSnapshot stale = snapshot(anchor, 60, 90, true);
        PositionSnapshot stationary = snapshot(anchor, 0, 90, false);

        assertSame(stale, stale.predictAt(anchor.plusSeconds(3_600)));
        assertSame(stationary, stationary.predictAt(anchor.plusSeconds(3_600)));
    }

    private PositionSnapshot snapshot(
            Instant anchor,
            double speedKmh,
            Integer directionDeg,
            boolean stale
    ) {
        return new PositionSnapshot(
                "line-1", "vehicle-1", "粤A12345", "粤A12345",
                0, 0, speedKmh,
                "司机", "地址", "行驶", directionDeg, "方向",
                anchor, anchor, stale ? "stale-real" : "real", stale
        );
    }
}
