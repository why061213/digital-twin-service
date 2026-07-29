package com.jushen.digitaltwin.service;

import com.jushen.digitaltwin.config.WarehouseProperties;
import com.jushen.digitaltwin.model.WarehouseData;
import com.jushen.digitaltwin.web.dto.WarehouseChartManagementRequest;
import com.jushen.digitaltwin.web.dto.WarehouseCityManagementRequest;
import com.jushen.digitaltwin.web.dto.WarehouseDataAdjustmentRequest;
import com.jushen.digitaltwin.websocket.RealtimeWebSocketHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class WarehousePushServiceManagementTest {

    private WarehousePushService service;

    @BeforeEach
    void setUp() {
        WarehouseProperties properties = new WarehouseProperties();
        WarehouseProperties.WarehouseConfig foshan = new WarehouseProperties.WarehouseConfig();
        foshan.setCity("佛山市");
        foshan.setLabel("旧仓");
        properties.setWarehouses(List.of(foshan));
        WarehouseDataProvider provider = () -> List.of(new WarehouseData("佛山市", Map.of("inventory", 12)));
        service = new WarehousePushService(mock(RealtimeWebSocketHandler.class), provider, properties);
    }

    @Test
    void cityManagementAddsAndDeletesWholeCity() {
        service.applyCityManagement(new WarehouseCityManagementRequest("添加", List.of(
                new WarehouseCityManagementRequest.CityItem("440100", "广州市", "南沙仓")
        )));
        assertThat(service.getWarehouseSnapshot()).extracting(item -> item.get("cityName"))
                .containsExactly("佛山市", "广州市");

        service.applyCityManagement(new WarehouseCityManagementRequest("DELETE", List.of(
                new WarehouseCityManagementRequest.CityItem("440600", "佛山市", null)
        )));
        assertThat(service.getWarehouseSnapshot()).extracting(item -> item.get("cityName"))
                .containsExactly("广州市");
    }

    @Test
    void chartAddUsesFirstFreePositionAndDataAdjustmentMerges() {
        service.applyChartManagement(List.of(new WarehouseChartManagementRequest(
                "佛山市", "ADD", List.of(new WarehouseChartManagementRequest.ChartItem(
                null, "bar", Map.of("title", "吞吐量", "rows", List.of())
        )))));
        service.applyDataAdjustments(List.of(new WarehouseDataAdjustmentRequest(
                false, null, "佛山市", 1, Map.of("title", "今日吞吐量")
        )));
        @SuppressWarnings("unchecked")
        Map<String, Object> displayData = (Map<String, Object>) service.getWarehouseSnapshot().get(0).get("displayData");
        assertThat(displayData.get("charts").toString()).contains("今日吞吐量", "position=1", "chartType=bar");
    }

    @Test
    void invalidSidePanelPositionIsRejected() {
        assertThatThrownBy(() -> service.applyDataAdjustments(List.of(new WarehouseDataAdjustmentRequest(
                true, null, "佛山市", 5, Map.of("rows", List.of())
        )))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("between 1 and 4");
    }

    @Test
    void unconfiguredPullDoesNotMutateData() {
        assertThat(service.pullCities()).containsEntry("configured", false).containsEntry("applied", 0);
        assertThat(service.getWarehouseSnapshot()).hasSize(1);
    }
}
