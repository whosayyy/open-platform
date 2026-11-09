package com.yin.ai.platform.agent.pattern;

import java.time.Instant;

public record IntermediateArtifact(
        String artifactId, String taskId, String stepId, String name,
        String mediaType, String objectKey, String url, long size, Instant createdAt
) { }
