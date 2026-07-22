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
    void diagnosticSwitchRecognizesLoadingAndUnloadingStatusesOnly() {
        assertThat(TownRoadRenderService.shouldTreatAsTransporting("待装载")).isTrue();
        assertThat(TownRoadRenderService.shouldTreatAsTransporting("装货中")).isTrue();
        assertThat(TownRoadRenderService.shouldTreatAsTransporting("卸载中")).isTrue();
        assertThat(TownRoadRenderService.shouldTreatAsTransporting("车辆装卸中")).isTrue();
        assertThat(TownRoadRenderService.shouldTreatAsTransporting("运输中")).isFalse();
        assertThat(TownRoadRenderService.shouldTreatAsTransporting("卸货完成")).isFalse();
        assertThat(TownRoadRenderService.shouldTreatAsTransporting("已取消")).isFalse();
    }
}
