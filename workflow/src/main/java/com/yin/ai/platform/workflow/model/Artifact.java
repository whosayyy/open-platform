package com.yin.ai.platform.workflow.model;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * 步骤间传递的结构化产物。
 *
 * <p>content 为已解析的结构化对象（Map/List 或业务模型），
 * metadata 承载正文长度、摘要 Hash 等可观测信息（禁止写入完整敏感正文）。
 */
public record Artifact(
        String artifactId,
        String taskRunId,
        String producerStepId,
        ArtifactType type,
        String schemaVersion,
        Object content,
        Map<String, Object> metadata,
        Instant createdAt
) {

    public static Artifact of(String taskRunId, String producerStepId, ArtifactType type, Object content) {
        return new Artifact(
                java.util.UUID.randomUUID().toString(),
                taskRunId,
                producerStepId,
                type,
                "1.0.0",
                content,
                new HashMap<>(),
                Instant.now());
    }

    public Artifact withMetadata(String key, Object value) {
        Map<String, Object> meta = new HashMap<>(metadata == null ? new HashMap<>() : metadata);
        meta.put(key, value);
        return new Artifact(artifactId, taskRunId, producerStepId, type, schemaVersion, content, meta, createdAt);
    }
}
