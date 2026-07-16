package com.jushen.digitaltwin.dto;

import java.util.List;

/** One node in the RM2 province -> direction -> display-group hierarchy. */
public record Rm2ChainNodeDTO(
        String nodeId,
        String nodeType,
        String parentNodeId,
        String key,
        String label,
        int index,
        String nextNodeId,
        List<String> childNodeIds,
        String groupId
) {
    public Rm2ChainNodeDTO {
        if (childNodeIds == null) childNodeIds = List.of();
    }
}
