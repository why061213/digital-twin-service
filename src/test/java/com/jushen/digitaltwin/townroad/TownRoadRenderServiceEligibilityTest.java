package com.jushen.digitaltwin.townroad;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TownRoadRenderServiceEligibilityTest {
    @Test
    void strictModeRequiresFreshPositionNotOnlyResolvableVehicleId() {
        assertThat(TownRoadRenderService.isEligibleForRealPositionMode(true, true, false)).isFalse();
        assertThat(TownRoadRenderService.isEligibleForRealPositionMode(true, true, true)).isTrue();
    }

    @Test
    void nonStrictModeMayStillRenderOrdersWithoutProviderPosition() {
        assertThat(TownRoadRenderService.isEligibleForRealPositionMode(false, false, false)).isTrue();
    }
}
