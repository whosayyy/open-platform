package com.yin.ai.platform.agent.tool;

import com.yin.ai.platform.agent.event.AgentEvent;
import com.yin.ai.platform.agent.event.EventPublisher;
import com.yin.ai.platform.agent.pattern.Artifact;
import com.yin.ai.platform.agent.task.ContextStore;
import com.yin.ai.platform.function.*;

import java.util.List;
import java.util.Map;

/**
 * append_artifact — 保存最终产物。
 */
public class AppendArtifactFunction extends AbstractFunction {

    private final ContextStore contextStore;
    private final EventPublisher eventPublisher;

    public AppendArtifactFunction(ContextStore contextStore, EventPublisher eventPublisher) {
        this.contextStore = contextStore;
        this.eventPublisher = eventPublisher;
    }

    @Override
    public FunctionDefinition getDefinition() {
        return FunctionDefinition.builder()
                .name("append_artifact")
                .description("保存最终产物（文章、报告、代码等）。任务完成前调用，将最终交付物持久化。")
                .category("agent-control")
                .risk(FunctionDefinition.Risk.WRITE)
                .callableByLLM(true)
                .parameters(List.of(
                        FunctionDefinition.ParamDef.builder()
                                .name("name").type(FunctionDefinition.ParamType.STRING)
                                .required(true).description("文件名（如 report.md）").build(),
                        FunctionDefinition.ParamDef.builder()
                                .name("type").type(FunctionDefinition.ParamType.STRING)
                                .required(true).description("类型（markdown/json/html/pdf）").build(),
                        FunctionDefinition.ParamDef.builder()
                                .name("content").type(FunctionDefinition.ParamType.STRING)
                                .required(true).description("产物内容").build()
                ))
                .build();
    }

    @Override
    protected FunctionResult doExecute(FunctionInput input) {
        Map<String, Object> params = input.getParameters();
        String taskId = AgentExecutionContextHolder.currentTaskId();
        String name = (String) params.get("name");
        String type = (String) params.get("type");
        String content = (String) params.get("content");

        Artifact artifact = Artifact.of(name, type, content, "tool");
        contextStore.saveArtifactAsync(taskId, artifact);
        eventPublisher.publish(AgentEvent.artifactUpdate(taskId, artifact));

        return FunctionResult.ok(Map.of(
                "artifactId", artifact.id(),
                "name", name,
                "saved", true));
    }
}
