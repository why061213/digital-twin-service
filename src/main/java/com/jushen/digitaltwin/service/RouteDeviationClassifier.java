package com.jushen.digitaltwin.service;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 基于连续真实定位证据的偏移事件状态机。单个离线点或单次离路永远不会直接报警。
 */
final class RouteDeviationClassifier {
    enum State { BASELINE, SUSPECTED, ALTERNATIVE, EXPECTED, ANOMALOUS, UNKNOWN }

    record Sample(
            Instant providerTime,
            double[] position,
            double baselineProgress,
            double routeProgress,
            double destinationDistanceKm,
            double offBaselineKm,
            double speedKmh,
            Integer directionDeg,
            boolean stale
    ) {}

    record Decision(
            State state,
            String reasonCode,
            double confidence,
            int anomalyScore,
            int anomalyStreak,
            int sampleCount
    ) {
        boolean shouldWarn() { return state == State.ANOMALOUS && anomalyStreak >= 3; }
        boolean shouldCritical() { return state == State.ANOMALOUS && anomalyStreak >= 5; }
    }

    private static final int MAX_SAMPLES = 12;
    private final Map<String, Tracker> trackers = new ConcurrentHashMap<>();

    Decision observe(String lineId, Sample sample, boolean knownPattern) {
        if (sample == null || sample.stale() || sample.position() == null
                || sample.position().length < 2 || sample.providerTime() == null) {
            return new Decision(State.UNKNOWN, "UNRELIABLE_POSITION", 0, 0, 0, 0);
        }
        Tracker tracker = trackers.computeIfAbsent(lineId, ignored -> new Tracker());
        synchronized (tracker) {
            if (sample.providerTime().equals(tracker.lastProviderTime)) return tracker.lastDecision;
            tracker.lastProviderTime = sample.providerTime();
            tracker.samples.addLast(sample);
            while (tracker.samples.size() > MAX_SAMPLES) tracker.samples.removeFirst();

            if (sample.offBaselineKm() <= 0.75) {
                tracker.anomalyStreak = 0;
                tracker.samples.clear();
                tracker.samples.add(sample);
                return tracker.lastDecision = new Decision(
                        State.BASELINE, "ON_BASELINE", 1, 0, 0, tracker.samples.size());
            }
            if (tracker.samples.size() < 3) {
                tracker.anomalyStreak = 0;
                return tracker.lastDecision = new Decision(
                        State.SUSPECTED, "INSUFFICIENT_CONTINUOUS_SAMPLES", 0.35,
                        0, 0, tracker.samples.size());
            }

            Sample first = tracker.samples.peekFirst();
            Sample previous = previous(tracker.samples);
            int anomalyScore = 0;
            if (sample.baselineProgress() + 0.02 < first.baselineProgress()) anomalyScore += 2;
            if (sample.destinationDistanceKm() > first.destinationDistanceKm() + 0.5) anomalyScore += 2;
            if (previous != null && sample.routeProgress() + 0.02 < previous.routeProgress()) anomalyScore += 1;
            if (hasRepeatedReversal(tracker.samples)) anomalyScore += 2;
            if (headingContradictsMovement(previous, sample)) anomalyScore += 1;
            if (isUnexplainedStop(first, sample)) anomalyScore += 1;

            boolean stableForward = sample.destinationDistanceKm() <= first.destinationDistanceKm() + 0.15
                    && sample.routeProgress() + 0.01 >= first.routeProgress()
                    && !hasRepeatedReversal(tracker.samples);
            if (anomalyScore >= 3) tracker.anomalyStreak++;
            else tracker.anomalyStreak = Math.max(0, tracker.anomalyStreak - 1);

            if (tracker.anomalyStreak >= 3) {
                double confidence = Math.min(0.98, 0.55 + tracker.anomalyStreak * 0.08);
                return tracker.lastDecision = new Decision(
                        State.ANOMALOUS, "SUSTAINED_ANOMALY_SCORE", confidence,
                        anomalyScore, tracker.anomalyStreak, tracker.samples.size());
            }
            if (knownPattern && stableForward) {
                return tracker.lastDecision = new Decision(
                        State.EXPECTED, "REPEATED_TRUCK_PATTERN", 0.85,
                        anomalyScore, tracker.anomalyStreak, tracker.samples.size());
            }
            if (stableForward) {
                double confidence = Math.min(0.9, 0.55 + tracker.samples.size() * 0.04);
                return tracker.lastDecision = new Decision(
                        State.ALTERNATIVE, "STABLE_FORWARD_ALTERNATIVE", confidence,
                        anomalyScore, tracker.anomalyStreak, tracker.samples.size());
            }
            return tracker.lastDecision = new Decision(
                    State.SUSPECTED, "EVIDENCE_NOT_YET_STABLE", 0.45,
                    anomalyScore, tracker.anomalyStreak, tracker.samples.size());
        }
    }

    Decision decision(String lineId) {
        Tracker tracker = trackers.get(lineId);
        return tracker == null ? null : tracker.lastDecision;
    }

    void remove(String lineId) { trackers.remove(lineId); }

    private static Sample previous(Deque<Sample> samples) {
        Sample previous = null;
        Sample current = null;
        for (Sample sample : samples) {
            previous = current;
            current = sample;
        }
        return previous;
    }

    private static boolean hasRepeatedReversal(Deque<Sample> samples) {
        int reversals = 0;
        Sample prior = null;
        Double priorBearing = null;
        for (Sample sample : samples) {
            if (prior != null && distanceKm(prior.position(), sample.position()) >= 0.03) {
                double bearing = bearing(prior.position(), sample.position());
                if (priorBearing != null && angleDifference(priorBearing, bearing) >= 135) reversals++;
                priorBearing = bearing;
            }
            prior = sample;
        }
        return reversals >= 2;
    }

    private static boolean headingContradictsMovement(Sample previous, Sample current) {
        if (previous == null || current.directionDeg() == null
                || distanceKm(previous.position(), current.position()) < 0.05) return false;
        return angleDifference(current.directionDeg(), bearing(previous.position(), current.position())) > 100;
    }

    private static boolean isUnexplainedStop(Sample first, Sample current) {
        long seconds = current.providerTime().getEpochSecond() - first.providerTime().getEpochSecond();
        return seconds >= 600 && current.speedKmh() <= 1
                && distanceKm(first.position(), current.position()) < 0.1
                && current.destinationDistanceKm() > 1;
    }

    private static double bearing(double[] from, double[] to) {
        double lat1 = Math.toRadians(from[1]);
        double lat2 = Math.toRadians(to[1]);
        double deltaLng = Math.toRadians(to[0] - from[0]);
        double y = Math.sin(deltaLng) * Math.cos(lat2);
        double x = Math.cos(lat1) * Math.sin(lat2)
                - Math.sin(lat1) * Math.cos(lat2) * Math.cos(deltaLng);
        return (Math.toDegrees(Math.atan2(y, x)) + 360) % 360;
    }

    private static double angleDifference(double left, double right) {
        double difference = Math.abs(left - right) % 360;
        return Math.min(difference, 360 - difference);
    }

    private static double distanceKm(double[] start, double[] end) {
        double lat1 = Math.toRadians(start[1]);
        double lat2 = Math.toRadians(end[1]);
        double dLat = lat2 - lat1;
        double dLng = Math.toRadians(end[0] - start[0]);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(lat1) * Math.cos(lat2) * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 6_371.0088 * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    private static final class Tracker {
        private final Deque<Sample> samples = new ArrayDeque<>();
        private Instant lastProviderTime;
        private int anomalyStreak;
        private Decision lastDecision = new Decision(State.UNKNOWN, "NO_SAMPLE", 0, 0, 0, 0);
    }
}
