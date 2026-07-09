package com.jushen.digitaltwin.townroad;

import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/road/town")
public class TownRoadController {

    private final TownRoadRenderService renderService;

    public TownRoadController(TownRoadRenderService renderService) {
        this.renderService = renderService;
    }

    /**
     * 正式入口：
     * 后端 POST 调外部接口 -> 拿 ExternalOrderRecord[] -> 中间层处理 -> broadcast town_road_render[]
     */
    @PostMapping("/provinces")
    public Map<String, Object> pushProvinceRenderCommand(
            @RequestBody(required = false) Map<String, Object> payload
    ) {
        return renderService.fetchProcessAndBroadcast(payload == null ? Map.of() : payload);
    }

    /**
     * 联调用：
     * 直接把一批 ExternalOrderRecord[] POST 进来，不经过外部接口。
     */
    @PostMapping("/provinces/raw")
    public Map<String, Object> pushRawOrders(
            @RequestBody List<ExternalOrderRecord> rawOrders
    ) {
        return renderService.processAndBroadcast(rawOrders);
    }

    /**
     * 查同起点目的地订单。
     */
    @GetMapping("/same-od")
    public Map<String, Object> sameOd(
            @RequestParam String fromKey,
            @RequestParam String toKey
    ) {
        List<NormalizedTownRoadOrder> orders = renderService.middleLayer().findSameOd(fromKey, toKey);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("ok", true);
        response.put("fromKey", fromKey);
        response.put("toKey", toKey);
        response.put("count", orders.size());
        response.put("orders", orders);
        return response;
    }

    /**
     * 查同一个省份路径下的订单。
     * 例如：440000>350000
     */
    @GetMapping("/province-path")
    public Map<String, Object> provincePath(
            @RequestParam String pathKey,
            @RequestParam(defaultValue = "route") String mode
    ) {
        List<NormalizedTownRoadOrder> orders;
        Object resolvedPathKeys;

        if ("exact".equalsIgnoreCase(mode)) {
            resolvedPathKeys = List.of(pathKey);
            orders = renderService.middleLayer().findByProvincePath(pathKey);
        } else {
            resolvedPathKeys = renderService.middleLayer().resolveProvincePathKeys(pathKey);
            orders = renderService.middleLayer().findByProvinceRoute(pathKey);
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("ok", true);
        response.put("mode", mode);
        response.put("pathKey", pathKey);
        response.put("resolvedPathKeys", resolvedPathKeys);
        response.put("count", orders.size());
        response.put("orders", orders);
        return response;
    }

    /**
     * 当前中间层缓存里的所有订单。
     */
    @GetMapping("/orders")
    public Map<String, Object> allOrders() {
        List<NormalizedTownRoadOrder> orders = renderService.middleLayer().allOrders();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("ok", true);
        response.put("count", orders.size());
        response.put("orders", orders);
        return response;
    }
}