package com.yin.ai.platform.memory.orchestration;

import com.yin.ai.platform.core.memory.RelationType;

import java.util.List;

public record ConflictResolutionResult(RelationType relation, String activeMemoryId,
                                       List<String> affectedIds, String reason) {
    public ConflictResolutionResult {
        affectedIds = affectedIds != null ? List.copyOf(affectedIds) : List.of();
        reason = reason != null ? reason : "";
    }
}
