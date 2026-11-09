package com.yin.ai.platform.agent.pattern;

import java.time.Instant;

/**
 * 任务产物 — Agent 执行过程中生成的最终交付物。
 * <p>
 * 与 Context 的区别：Context 是步骤间传递的中间数据，Artifact 是面向用户的最终产出。
 * 小内容直接存 content 字段，大文件存 OSS URL。
 *
 * @param id        产物唯一标识
 * @param name      文件名（如 "article.md"）
 * @param type      MIME 类型标识（如 "markdown", "json", "html", "pdf"）
 * @param content   产物内容（或 OSS URL，当 size > 阈值时）
 * @param stepId    产生该产物的步骤 ID
 * @param createdAt 创建时间
 */
public record Artifact(
        String id,
        String name,
        String type,
        String content,
        String stepId,
        Instant createdAt
) {
    public static Artifact of(String name, String type, String content, String stepId) {
        return new Artifact(
                java.util.UUID.randomUUID().toString(),
                name, type, content, stepId, Instant.now()
        );
    }
}
