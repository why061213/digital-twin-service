package com.jushen.digitaltwin.townroad;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class WaitingOrderTrajectoryClassifierTest {
    private static final double[] LOADING = {113.0, 23.0};

    @Test
    void currentPositionInsideFiveKilometersIsLoading() {
        var result = WaitingOrderTrajectoryClassifier.classify(
                new double[]{113.01, 23.0}, LOADING, List.of());

        assertThat(result.state()).isEqualTo(WaitingOrderTrajectoryClassifier.State.LOADING);
        assertThat(result.groupEligible()).isFalse();
    }

    @Test
    void decreasingDistanceMeansVehicleIsGoingToLoadingPoint() {
        List<ProviderTrajectoryClient.TrackPoint> points = List.of(
                point(0, 113.14), point(1, 113.12), point(2, 113.10),
                point(3, 113.08), point(4, 113.07));

        var result = WaitingOrderTrajectoryClassifier.classify(
                new double[]{113.06, 23.0}, LOADING, points);

        assertThat(result.state()).isEqualTo(WaitingOrderTrajectoryClassifier.State.TO_LOADING);
        assertThat(result.groupEligible()).isFalse();
    }

    @Test
    void twoConsecutiveInsideNodesThenMovingAwayMeansDeparted() {
        List<ProviderTrajectoryClient.TrackPoint> points = List.of(
                point(0, 113.02), point(1, 113.03), point(2, 113.045), point(3, 113.06));

        var result = WaitingOrderTrajectoryClassifier.classify(
                new double[]{113.09, 23.0}, LOADING, points);

        assertThat(result.consecutiveLoadingDwell()).isTrue();
        assertThat(result.state()).isEqualTo(WaitingOrderTrajectoryClassifier.State.DEPARTED);
        assertThat(result.groupEligible()).isTrue();
    }

    @Test
    void movingAwayWithoutLoadingDwellRemainsUnknown() {
        List<ProviderTrajectoryClient.TrackPoint> points = List.of(
                point(0, 113.07), point(1, 113.08), point(2, 113.09));

        var result = WaitingOrderTrajectoryClassifier.classify(
                new double[]{113.11, 23.0}, LOADING, points);

        assertThat(result.state()).isEqualTo(WaitingOrderTrajectoryClassifier.State.UNKNOWN);
        assertThat(result.groupEligible()).isFalse();
    }

    @Test
    void fullTwoMinuteSeriesIsSampledIntoAtMostThirtySixTimeQuantiles() {
        List<ProviderTrajectoryClient.TrackPoint> points = new ArrayList<>();
        for (int i = 0; i < 100; i++) points.add(point(i, 113.1));

        List<ProviderTrajectoryClient.TrackPoint> sampled =
                WaitingOrderTrajectoryClassifier.downsample(points, 36);

        assertThat(sampled).hasSize(36);
        assertThat(sampled.get(0).time()).isEqualTo(points.get(0).time());
        assertThat(sampled.get(35).time()).isEqualTo(points.get(99).time());
    }

    private ProviderTrajectoryClient.TrackPoint point(int index, double lng) {
        return new ProviderTrajectoryClient.TrackPoint(
                Instant.parse("2026-07-23T00:00:00Z").plusSeconds(index * 120L), lng, 23.0);
    }
}
