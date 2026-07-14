package com.jushen.digitaltwin.web;

import com.jushen.digitaltwin.dto.RenderRouteDTO;
import com.jushen.digitaltwin.dto.RenderRouteGroupDTO;
import com.jushen.digitaltwin.dto.RouteDtoConverter;
import com.jushen.digitaltwin.townroad.TownRoadRenderService;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RM2（短途）正式 REST 接口。
 * 数据来源：TownRoadRenderService 中缓存的最近一次中间层处理结果。
 */
@RestController
@RequestMapping("/api/road/rm2")
public class Rm2Controller {

    private final TownRoadRenderService renderService;

    public Rm2Controller(TownRoadRenderService renderService) {
        this.renderService = renderService;
    }

    /**
     * 获取 RM2 路线分组列表。
     */
    @GetMapping("/groups")
    public Map<String, Object> listGroups() {
        List<RenderRouteDTO> routes = renderService.getLatestRm2Routes();
        List<RenderRouteGroupDTO> groups = renderService.getLatestRm2Groups();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("snapshotVersion", renderService.getLatestSnapshotVersion());
        response.put("scope", "rm2");
        response.put("groupSize", 12);
        response.put("totalRoutes", routes.size());
        response.put("groups", groups);
        return response;
    }

    /**
     * 获取指定组的路线详情。
     */
    @GetMapping("/groups/{groupId}/routes")
    public Map<String, Object> listGroupRoutes(@PathVariable String groupId) {
        List<RenderRouteDTO> allRoutes = renderService.getLatestRm2Routes();
        List<RenderRouteGroupDTO> allGroups = renderService.getLatestRm2Groups();

        // 找到对应组
        RenderRouteGroupDTO targetGroup = allGroups.stream()
                .filter(g -> g.groupId().equals(groupId))
                .findFirst()
                .orElse(null);

        List<RenderRouteDTO> groupRoutes;
        if (targetGroup != null && targetGroup.orderLineIds() != null) {
            var lineIdSet = new java.util.LinkedHashSet<>(targetGroup.orderLineIds());
            groupRoutes = allRoutes.stream()
                    .filter(r -> lineIdSet.contains(r.lineId()))
                    .toList();
        } else {
            groupRoutes = List.of();
        }

        // 检查坐标有效性，异常路线放入 rejected
        List<RenderRouteDTO> validRoutes = new ArrayList<>();
        List<Map<String, Object>> rejected = new ArrayList<>();
        for (RenderRouteDTO route : groupRoutes) {
            if (isValidRoute(route)) {
                validRoutes.add(route);
            } else {
                Map<String, Object> rejectEntry = new LinkedHashMap<>();
                rejectEntry.put("lineId", route.lineId());
                rejectEntry.put("reason", describeRejection(route));
                rejected.add(rejectEntry);
            }
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("snapshotVersion", renderService.getLatestSnapshotVersion());
        response.put("scope", "rm2");
        response.put("groupId", groupId);
        response.put("coordinateSystem", "GCJ02");
        response.put("routes", validRoutes);
        response.put("rejected", rejected);
        return response;
    }

    private boolean isValidRoute(RenderRouteDTO route) {
        if (route.coordinates() == null || route.coordinates().size() < 2) return false;
        for (double[] c : route.coordinates()) {
            if (c == null || c.length < 2) return false;
            if (!Double.isFinite(c[0]) || !Double.isFinite(c[1])) return false;
            // 经度范围检查
            if (c[0] < -180 || c[0] > 180) return false;
            // 纬度范围检查
            if (c[1] < -90 || c[1] > 90) return false;
        }
        return true;
    }

    private String describeRejection(RenderRouteDTO route) {
        if (route.coordinates() == null || route.coordinates().isEmpty()) return "缺少坐标";
        if (route.coordinates().size() < 2) return "坐标点不足2个";
        for (double[] c : route.coordinates()) {
            if (c == null || c.length < 2) return "坐标格式错误";
            if (!Double.isFinite(c[0]) || !Double.isFinite(c[1])) return "坐标含NaN或Infinity";
            if (c[0] < -180 || c[0] > 180) return "经度越界";
            if (c[1] < -90 || c[1] > 90) return "纬度越界";
        }
        return "未知原因";
    }
}
