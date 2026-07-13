package com.jushen.digitaltwin.townroad;

import com.jushen.digitaltwin.websocket.RealtimeWebSocketHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class TownRoadRenderService {

    private static final Logger log = LoggerFactory.getLogger(TownRoadRenderService.class);

    private final TownRoadExternalOrderClient townRoadExternalOrderClient;
    private final TownRoadMiddleLayer middleLayer;
    private final RealtimeWebSocketHandler realtimeWebSocketHandler;
    private final AmapGeocodeClient amapGeocodeClient;
    private final TownRoadCoordinateResolver coordinateResolver;
    private Map<String, Object> lastResult = Map.of();

    public Map<String, Object> latestResult() {
        return lastResult;
    }

    public TownRoadRenderService(
            TownRoadExternalOrderClient townRoadExternalOrderClient,
            TownRoadMiddleLayer middleLayer,
            RealtimeWebSocketHandler realtimeWebSocketHandler,
            AmapGeocodeClient amapGeocodeClient,
            TownRoadCoordinateResolver coordinateResolver
    ) {
        this.townRoadExternalOrderClient = townRoadExternalOrderClient;
        this.middleLayer = middleLayer;
        this.realtimeWebSocketHandler = realtimeWebSocketHandler;
        this.amapGeocodeClient = amapGeocodeClient;
        this.coordinateResolver = coordinateResolver;
    }

    public Map<String, Object> fetchProcessAndBroadcast() {
        List<ExternalOrderRecord> rawOrders = townRoadExternalOrderClient.fetchOrders();
        return processAndBroadcast(rawOrders);
    }

    public Map<String, Object> processAndBroadcast(List<ExternalOrderRecord> rawOrders) {
        ExternalOrderSnapshotResult result = middleLayer.processSnapshot(rawOrders);

        for (TownRoadRenderCommand command : result.commands()) {
            realtimeWebSocketHandler.broadcast(command);
        }

        int deletedOrCancelled = result.diff().deletedOrCancelled();
        int rawCount = result.rawCount();
        int normalizedCount = result.normalizedCount();
        int shortHaulCount = result.shortHaulCount();
        int skippedInvalid = result.diff().skippedInvalid();
        int skippedNotRenderable = result.diff().skippedNotRenderable();
        int skippedLongHaul = result.diff().skippedLongHaul();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("ok", true);
        response.put("type","town_road_render");
        response.put("message", "town_road_render commands broadcasted");
        response.put("rawCount", rawCount);
        response.put("normalizedCount", normalizedCount);
        response.put("shortHaulCount", shortHaulCount);
        response.put("commandCount", result.commands().size());
        response.put("displayMode", result.commands().size() > 1 ? "multi_source_rotation" : "single_source");
        response.put("diff", result.diff().toMap());

        // 完整数据流水账
        int skippedByStatus = normalizedCount - shortHaulCount - skippedNotRenderable - skippedLongHaul;
        Map<String, Object> accounting = new LinkedHashMap<>();
        accounting.put("rawCount", rawCount);
        accounting.put("    ─ skippedInvalid (基础校验失败)", skippedInvalid);
        accounting.put("    ─ deletedOrCancelled (已删除/已取消)", deletedOrCancelled);
        accounting.put("    = normalizedCount (有效订单)", normalizedCount);
        accounting.put("        ─ skippedNotRenderable (缺坐标)", skippedNotRenderable);
        accounting.put("        ─ skippedLongHaul (非短途)", skippedLongHaul);
        accounting.put("        ─ skippedByStatus (已完成/待装载)", skippedByStatus);
        accounting.put("        = shortHaulCount (最终渲染)", shortHaulCount);
        accounting.put("校验", rawCount + " = " + skippedInvalid + " + " + deletedOrCancelled + " + " + normalizedCount
                + (rawCount == skippedInvalid + deletedOrCancelled + normalizedCount ? " ✅" : " ❌ 对不上!"));
        accounting.put("渲染过滤", normalizedCount + " = " + skippedNotRenderable + " + " + skippedLongHaul + " + " + skippedByStatus + " + " + shortHaulCount
                + (normalizedCount == skippedNotRenderable + skippedLongHaul + skippedByStatus + shortHaulCount ? " ✅" : " ❌ 对不上!"));
        response.put("accounting", accounting);

        response.put("commands", result.commands());
        this.lastResult = response;

        // 打印统计
        log.info(coordinateResolver.getStatsAndReset());
        log.info(amapGeocodeClient.getStatsAndReset());

        return response;
    }

    public TownRoadMiddleLayer middleLayer() {
        return middleLayer;
    }
}