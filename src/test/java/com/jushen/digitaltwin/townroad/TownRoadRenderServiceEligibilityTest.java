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

    @Test
    void confirmedAndInferredTransitAreBothRuntimeDispatchable() {
        assertThat(TownRoadRenderService.isTransitPipelineStatus("在途-1")).isTrue();
        assertThat(TownRoadRenderService.isTransitPipelineStatus("在途-2")).isTrue();
        assertThat(TownRoadRenderService.isTransitPipelineStatus("运输中")).isTrue();
        assertThat(TownRoadRenderService.isTransitPipelineStatus("待装载")).isFalse();
    }

}
