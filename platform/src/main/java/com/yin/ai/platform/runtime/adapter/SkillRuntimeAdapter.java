package com.yin.ai.platform.runtime.adapter;

import com.yin.ai.platform.runtime.SkillExecutionContext;
import com.yin.ai.platform.runtime.SkillResult;
import com.yin.ai.platform.runtime.SkillRuntime;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

/**
 * SkillRuntime 便捷适配器。
 * <p>
 * 封装了常用 Skill 的调用方式（chat、review、summary、search），
 * 业务模块可直接注入使用，无需自行构建 {@link SkillExecutionContext}。
 * <p>
 * userId 在自动流程（review/summary 由 MQ 触发）中可为 null。
 */
@Slf4j
@Component("platformSkillRuntimeAdapter")
public class SkillRuntimeAdapter {

    private final SkillRuntime skillRuntime;

    public SkillRuntimeAdapter(SkillRuntime skillRuntime) {
        this.skillRuntime = skillRuntime;
    }

    public SkillResult executeChat(String userInput, Long userId, String sessionId) {
        SkillExecutionContext ctx = SkillExecutionContext.builder()
                .traceId(UUID.randomUUID().toString())
                .userInput(userInput)
                .userId(userId)
                .sessionId(sessionId)
                .build();
        return skillRuntime.execute("chat", ctx);
    }

    public SkillResult executeReview(String content, Long userId) {
        SkillExecutionContext ctx = SkillExecutionContext.builder()
                .traceId(UUID.randomUUID().toString())
                .userInput(content)
                .userId(userId)
                .attributes(Map.of(SkillExecutionContext.ATTR_SKIP_TOOLS, true))
                .build();
        return skillRuntime.execute("review", ctx);
    }

    public SkillResult executeSummary(String content, Long userId) {
        SkillExecutionContext ctx = SkillExecutionContext.builder()
                .traceId(UUID.randomUUID().toString())
                .userInput(content)
                .userId(userId)
                .attributes(Map.of(SkillExecutionContext.ATTR_SKIP_TOOLS, true))
                .build();
        return skillRuntime.execute("summary", ctx);
    }

    public SkillResult executeSearch(String query, Long userId) {
        SkillExecutionContext ctx = SkillExecutionContext.builder()
                .traceId(UUID.randomUUID().toString())
                .userInput(query)
                .userId(userId)
                .build();
        return skillRuntime.execute("search", ctx);
    }
}
