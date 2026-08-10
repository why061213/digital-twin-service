package com.jushen.digitaltwin.service;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class RouteDeviationClassifierTest {
    @Test
    void stableProgressTowardDestinationBecomesAlternativeWithoutAlarm() {
        RouteDeviationClassifier classifier = new RouteDeviationClassifier();
        Instant start = Instant.parse("2026-07-22T00:00:00Z");

        RouteDeviationClassifier.Decision decision = null;
        for (int i = 0; i < 5; i++) {
            decision = classifier.observe("line-1", sample(
                    start.plusSeconds(i * 60L), 113.0 + i * 0.01, 23.0,
                    0.35 + i * 0.01, 0.2 + i * 0.05, 20 - i, 45), false);
        }

        assertThat(decision.state()).isEqualTo(RouteDeviationClassifier.State.ALTERNATIVE);
        assertThat(decision.shouldWarn()).isFalse();
    }

    @Test
    void onlySustainedAnomalyScoreProducesWarningThenCritical() {
        RouteDeviationClassifier classifier = new RouteDeviationClassifier();
        Instant start = Instant.parse("2026-07-22T00:00:00Z");
        RouteDeviationClassifier.Decision decision = null;
        for (int i = 0; i < 7; i++) {
            decision = classifier.observe("line-2", sample(
                    start.plusSeconds(i * 60L), 113.0 - i * 0.01, 23.0,
                    0.5 - i * 0.05, 0.5 - i * 0.04, 10 + i, 90), false);
        }

        assertThat(decision.state()).isEqualTo(RouteDeviationClassifier.State.ANOMALOUS);
        assertThat(decision.shouldCritical()).isTrue();
    }

    @Test
    void stalePositionIsUnknown() {
        RouteDeviationClassifier classifier = new RouteDeviationClassifier();
        RouteDeviationClassifier.Sample stale = new RouteDeviationClassifier.Sample(
                Instant.now(), new double[]{113, 23}, 0.2, 0.2,
                20, 3, 50, 0, true);

        assertThat(classifier.observe("line", stale, false).state())
                .isEqualTo(RouteDeviationClassifier.State.UNKNOWN);
    }

    private RouteDeviationClassifier.Sample sample(
            Instant time, double lng, double lat, double baselineProgress,
            double routeProgress, double destinationKm, int direction) {
        return new RouteDeviationClassifier.Sample(
                time, new double[]{lng, lat}, baselineProgress, routeProgress,
                destinationKm, 3, 60, direction, false);
    }
}
