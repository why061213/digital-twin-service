package com.jushen.digitaltwin.web;

import com.jushen.digitaltwin.dto.RenderRouteDTO;
import com.jushen.digitaltwin.dto.Rm2Snapshot;
import com.jushen.digitaltwin.townroad.TownRoadRenderService;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/road/rm2")
public class Rm2Controller {

    private final TownRoadRenderService renderService;

    public Rm2Controller(TownRoadRenderService renderService) {
        this.renderService = renderService;
    }

    @GetMapping("/groups")
    public Map<String, Object> listGroups() {
        Rm2Snapshot s = renderService.getLatestRm2Snapshot();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("snapshotVersion", s.snapshotVersion());
        response.put("scope", "rm2");
        response.put("groupSize", 12);
        response.put("totalRoutes", s.routes().size());
        response.put("groups", s.groups());
        return response;
    }

    @GetMapping("/groups/{groupId}/routes")
    public Map<String, Object> listGroupRoutes(
            @PathVariable String groupId,
            @RequestParam(required = false) String snapshotVersion
    ) {
        Rm2Snapshot s = renderService.getLatestRm2Snapshot();

        // 版本不一致：返回当前版本让前端重新同步 groups
        if (snapshotVersion != null && !snapshotVersion.equals(s.snapshotVersion())) {
            Map<String, Object> mismatch = new LinkedHashMap<>();
            mismatch.put("scope", "rm2");
            mismatch.put("snapshotVersion", s.snapshotVersion());
            mismatch.put("groupId", groupId);
            mismatch.put("routes", List.of());
            mismatch.put("mismatch", true);
            return mismatch;
        }

        List<RenderRouteDTO> groupRoutes = s.routesByGroupId().getOrDefault(groupId, List.of());

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("scope", "rm2");
        response.put("snapshotVersion", s.snapshotVersion());
        response.put("groupId", groupId);
        response.put("routes", groupRoutes);
        return response;
    }
}
