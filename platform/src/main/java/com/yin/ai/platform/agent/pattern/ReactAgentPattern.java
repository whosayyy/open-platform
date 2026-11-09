package com.yin.ai.platform.agent.pattern;

import com.yin.ai.platform.agent.AgentContext;
import com.yin.ai.platform.agent.ReActLoop;
import com.yin.ai.platform.agent.event.AgentEvent;
import com.yin.ai.platform.agent.event.EventPublisher;
import com.yin.ai.platform.agent.event.TaskEventType;
import com.yin.ai.platform.agent.task.TaskState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * REACT 模式 — 包装现有 ReActLoop，适配统一 Pattern 接口。
 * <p>
 * 适用场景：需要工具调用的任务（搜索、计算、API 调用等）。
 * 特点：复用现有 ReActLoop 全部能力（Planner/Reflector/Observer/HITL），
 *       仅在外部包装事件发射和 Context 同步。
 */
public class ReactAgentPattern implements AgentPattern {

    private static final Logger log = LoggerFactory.getLogger(ReactAgentPattern.class);

    /**
     * ReAct 循环中断恢复所需的状态键。HITL 暂停（waiting）时把内层
     * AgentContext 的这些变量复制到统一上下文快照持久化；恢复时反向回填。
     */
    private static final List<String> REACT_RESUME_KEYS = List.of(
            "agent.hitlPlan", "agent.hitlStepIndex", "agent.hitlStepTraces",
            "agent.resumePlan", "agent.resumeStepTraces", "agent.resumeStepIndex",
            "agent.checkpointPlan", "agent.checkpointNextStepIndex", "agent.checkpointStepId",
            "agent.checkpointStepOutput", "agent.checkpointStepDecision", "agent.checkpointStepTraces");

    private final ReActLoop reActLoop;

    public ReactAgentPattern(ReActLoop reActLoop) {
        this.reActLoop = reActLoop;
    }

    @Override
    public AgentMode mode() {
        return AgentMode.REACT;
    }

    @Override
    public Flux<AgentEvent> execute(AgentExecutionContext ctx) {
        return Flux.create(sink -> {
            String taskId = ctx.getTaskId();
            EventPublisher delegate = ctx.getEventPublisher();
            EventPublisher publisher = new EventPublisher() {
                @Override
                public void publish(AgentEvent event) {
                    delegate.publish(event);
                    sink.next(event);
                }

                @Override
                public reactor.core.publisher.Flux<AgentEvent> replay(String taskId, String lastEventId) {
                    return delegate.replay(taskId, lastEventId);
                }
            };
            ctx.setEventPublisher(publisher);

            try {
                boolean resuming = Boolean.TRUE.equals(ctx.getVariable("resume"));
                if (resuming) {
                    // WAITING(审批暂停)/FAILED(重试) → RUNNING
                    TaskState state = ctx.getState();
                    if (state == TaskState.WAITING || state == TaskState.FAILED) {
                        ctx.transitionTo(TaskState.RUNNING);
                    }
                } else if (ctx.getState() == TaskState.CREATED) {
                    ctx.transitionTo(TaskState.RUNNING);
                }

                publisher.publish(AgentEvent.stepStarted(taskId, "react-loop", "ReAct 推理循环"));
                var reasoning = ReasoningOptionsSupport.resolve(ctx, StepType.TOOL);
                publisher.publish(AgentEvent.reasoningStarted(taskId, "react-loop", reasoning));

                // 委托给现有 ReActLoop（enter 为同步执行）
                AgentContext agentCtx = ctx.getAgentContext();
                if (resuming) {
                    restoreAgentState(ctx, agentCtx);
                }
                com.yin.ai.platform.agent.AgentResult result = reActLoop.enter(agentCtx);
                if (result != null) {
                    ctx.accumulateTokenUsage(result.tokenUsage());
                }
                int usedTokens = result != null && result.tokenUsage() != null
                        && result.tokenUsage().getTotalTokens() != null
                        ? result.tokenUsage().getTotalTokens() : 0;
                publisher.publish(AgentEvent.reasoningCompleted(
                        taskId, "react-loop", reasoning, usedTokens));

                // 同步结果到 ExecContext
                ctx.putVariable("react_result", result);
                if (result != null && result.result() != null) {
                    ctx.addArtifact(Artifact.of("react-output.md", "markdown",
                            result.result(), "react-loop"));
                }

                // HITL 暂停：持久化恢复状态并切到 WAITING，等待 resume
                if (result != null && result.waiting()) {
                    persistAgentState(ctx, agentCtx);
                    if (ctx.getState() == TaskState.RUNNING) {
                        ctx.transitionTo(TaskState.WAITING);
                    }
                    publisher.publish(new AgentEvent(null, taskId, TaskEventType.TASK_WAITING,
                            null, Map.of("step", result.stepCount(), "reason", "awaiting_approval"),
                            Instant.now()));
                    sink.complete();
                    return;
                }

                publisher.publish(AgentEvent.stepCompleted(taskId, "react-loop",
                        result != null ? String.valueOf(result.result()) : "done"));
                sink.complete();

            } catch (Exception e) {
                log.error("[REACT] execution failed taskId={}", taskId, e);
                publisher.publish(AgentEvent.stepFailed(taskId, "react-loop", e.getMessage()));
                sink.error(e);
            }
        });
    }

    @Override
    public boolean resumable() {
        return true;  // HITL 暂停/断点恢复：状态经统一上下文快照持久化
    }

    /** 把内层 ReAct 循环的恢复状态复制到统一上下文（随快照持久化）。 */
    private void persistAgentState(AgentExecutionContext ctx, AgentContext agentCtx) {
        for (String key : REACT_RESUME_KEYS) {
            Object value = agentCtx.getVariable(key);
            if (value != null) {
                ctx.putVariable(key, value);
            }
        }
    }

    /** 恢复时把统一上下文中的 ReAct 状态回填到内层 AgentContext。 */
    private void restoreAgentState(AgentExecutionContext ctx, AgentContext agentCtx) {
        for (String key : REACT_RESUME_KEYS) {
            Object value = ctx.getVariable(key);
            if (value != null) {
                agentCtx.putVariable(key, value);
            }
        }
    }

    @Override
    public int priority() {
        return 100;
    }
}
