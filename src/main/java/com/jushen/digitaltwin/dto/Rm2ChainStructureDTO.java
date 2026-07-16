package com.jushen.digitaltwin.dto;

import java.util.List;

/** Immutable three-level RM2 playback structure for one snapshot. */
public record Rm2ChainStructureDTO(
        String headNodeId,
        List<Rm2ChainNodeDTO> nodes,
        List<String> leafGroupIds
) {
    public Rm2ChainStructureDTO {
        if (nodes == null) nodes = List.of();
        if (leafGroupIds == null) leafGroupIds = List.of();
    }

    public static Rm2ChainStructureDTO empty() {
        return new Rm2ChainStructureDTO(null, List.of(), List.of());
    }
}
