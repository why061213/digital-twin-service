package com.jushen.digitaltwin.model;

public record CityRouteEvent(
        String routeId,
        City from,
        City to,
        double value,
        int durationSeconds
) {
}
