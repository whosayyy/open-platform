package com.yin.ai.platform.agent.runtime;

import com.yin.ai.platform.agent.AgentContext;
import com.yin.ai.platform.agent.AgentEvent;
import com.yin.ai.platform.agent.AgentResult;
import com.yin.ai.platform.agent.AgentTask;
import reactor.core.publisher.Flux;

/**
 * Agent Runtime SPI — Agent 执行的统一入口。
 * <p>
 * Agent 是"多步自主推理"能力, Skill 是"单轮请求-响应"能力。
 * 80% 请求走 Skill, 20% 复杂请求走 Agent。
 */
@Deprecated(since = "0.0.1", forRemoval = false)
public interface AgentRuntime {

    /**
     * 同步执行 Agent 任务（阻塞直到完成或超时）。
     *
     * @param task Agent 任务
     * @return 执行结果
     */
    AgentResult execute(AgentTask task);

    /**
     * 流式执行 Agent 任务（SSE/WebSocket 推送每步进展）。
     *
     * @param task Agent 任务
     * @return 事件流
     */
    Flux<AgentEvent> executeStream(AgentTask task);

    /**
     * 取消正在执行的 Agent。
     *
     * @param agentId Agent 唯一标识
     */
    void cancel(String agentId);

    /**
     * 将子任务委派给隔离的 SubAgent 执行。
     *
     * @param subTask      子任务
     * @param parentContext 父 Agent 上下文
     * @return SubAgent 执行结果
     */
    SubAgentResult delegate(AgentTask subTask, AgentContext parentContext);
}
