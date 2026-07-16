package com.jushen.digitaltwin.web;

import com.jushen.digitaltwin.service.Rm2GroupQueryService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/road/rm2")
public class Rm2Controller {

    private final Rm2GroupQueryService queryService;

    public Rm2Controller(Rm2GroupQueryService queryService) {
        this.queryService = queryService;
    }

    @GetMapping("/groups")
    public Map<String, Object> listGroups(
            @RequestParam(required = false) String snapshotVersion
    ) {
        return queryService.listGroups(snapshotVersion);
    }

    @GetMapping("/groups/structure")
    public Map<String, Object> listStructure() {
        return queryService.listStructure();
    }

    @GetMapping("/groups/{groupId}/routes")
    public Map<String, Object> listGroupRoutes(
            @PathVariable String groupId,
            @RequestParam(required = false) String snapshotVersion
    ) {
        return queryService.listGroupRoutes(groupId, snapshotVersion);
    }
}
