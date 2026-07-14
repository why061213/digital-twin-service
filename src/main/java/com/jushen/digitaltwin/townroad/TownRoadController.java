package com.jushen.digitaltwin.townroad;

import com.jushen.digitaltwin.dto.RenderRouteDTO;
import com.jushen.digitaltwin.dto.RouteDtoConverter;
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
     * GET 调外部接口 -> 拿 ExternalOrderRecord[] -> 中间层处理 -> broadcast RouteSnapshotDTO[]
     */
    @PostMapping("/provinces")
    public Map<String, Object> pushProvinceRenderCommand() {
        return renderService.fetchProcessAndBroadcast();
    }

    /**
     * 联调用：
     * 直接把一批 ExternalOrderRecord[] POST 进来，不经过外部接口。
     */
    @PostMapping("/provinces/raw")
    public Map<String, Object> pushRawOrders(
            @RequestBody List<ExternalOrderRecord> rawOrders
    ) {
        return renderService.processAndBroadcastWithTrace(rawOrders);
    }

    /**
     * 查同起点目的地订单（已转为统一 DTO）。
     */
    @GetMapping("/same-od")
    public Map<String, Object> sameOd(
            @RequestParam String fromKey,
            @RequestParam String toKey
    ) {
        List<NormalizedTownRoadOrder> orders = renderService.middleLayer().findSameOd(fromKey, toKey);
        List<RenderRouteDTO> routes = RouteDtoConverter.shortHaulOrdersToRoutes(orders);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("ok", true);
        response.put("fromKey", fromKey);
        response.put("toKey", toKey);
        response.put("count", routes.size());
        response.put("routes", routes);
        return response;
    }

    /**
     * 查同一个省份路径下的订单（已转为统一 DTO）。
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

        List<RenderRouteDTO> routes = RouteDtoConverter.shortHaulOrdersToRoutes(orders);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("ok", true);
        response.put("mode", mode);
        response.put("pathKey", pathKey);
        response.put("resolvedPathKeys", resolvedPathKeys);
        response.put("count", routes.size());
        response.put("routes", routes);
        return response;
    }

    /**
     * 当前中间层缓存里的所有订单（已转为统一 DTO）。
     */
    @GetMapping("/orders")
    public Map<String, Object> allOrders() {
        List<NormalizedTownRoadOrder> orders = renderService.middleLayer().allOrders();
        List<RenderRouteDTO> routes = RouteDtoConverter.shortHaulOrdersToRoutes(orders);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("ok", true);
        response.put("count", routes.size());
        response.put("routes", routes);
        return response;
    }

    @GetMapping("/latest")
    public Map<String, Object> latest() {
        Map<String, Object> result = renderService.latestResult();

        if (result == null || result.isEmpty()) {
            Map<String, Object> empty = new LinkedHashMap<>();
            empty.put("ok", false);
            empty.put("message", "No town road snapshot has been processed yet");
            return empty;
        }

        return result;
    }
}
