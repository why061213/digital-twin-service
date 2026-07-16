package com.jushen.digitaltwin.service;

import com.jushen.digitaltwin.dto.RenderRouteDTO;
import com.jushen.digitaltwin.dto.Rm2Snapshot;
import com.jushen.digitaltwin.townroad.TownRoadRenderService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Builds the RM2 view of the shared road groups REST contract. */
@Service
public class Rm2GroupQueryService {

    private final TownRoadRenderService renderService;
    private final RoutePushService routePushService;

    public Rm2GroupQueryService(
            TownRoadRenderService renderService,
            RoutePushService routePushService
    ) {
        this.renderService = renderService;
        this.routePushService = routePushService;
    }

    public Map<String, Object> listGroups() {
        Rm2Snapshot snapshot = renderService.getLatestRm2Snapshot();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("snapshotVersion", snapshot.snapshotVersion());
        response.put("scope", "rm2");
        response.put("groupSize", TownRoadRenderService.RM2_GROUP_SIZE);
        response.put("totalRoutes", snapshot.routes().size());
        response.put("groups", snapshot.groups());
        return response;
    }

    public Map<String, Object> listGroupRoutes(String groupId, String snapshotVersion) {
        Rm2Snapshot snapshot = renderService.getLatestRm2Snapshot();
        if (snapshotVersion != null && !snapshotVersion.equals(snapshot.snapshotVersion())) {
            Map<String, Object> mismatch = new LinkedHashMap<>();
            mismatch.put("scope", "rm2");
            mismatch.put("snapshotVersion", snapshot.snapshotVersion());
            mismatch.put("groupId", groupId);
            mismatch.put("routes", List.of());
            mismatch.put("positions", List.of());
            mismatch.put("mismatch", true);
            return mismatch;
        }

        List<RenderRouteDTO> routes = snapshot.routesByGroupId().getOrDefault(groupId, List.of());
        List<Map<String, Object>> positions = new ArrayList<>();
        for (RenderRouteDTO route : routes) {
            Map<String, Object> position = routePushService.getCachedOrSimulatedPosition(route.lineId());
            if (position.containsKey("position") || "finished".equals(position.get("status"))) {
                positions.add(position);
            }
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("scope", "rm2");
        response.put("snapshotVersion", snapshot.snapshotVersion());
        response.put("groupId", groupId);
        response.put("coordinateSystem", "GCJ02");
        response.put("routes", routes);
        response.put("positions", positions);
        response.put("positionCount", positions.size());
        response.put("rejected", List.of());
        return response;
    }
}
