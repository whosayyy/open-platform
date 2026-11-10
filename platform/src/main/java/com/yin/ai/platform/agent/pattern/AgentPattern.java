package com.yin.ai.platform.agent.pattern;

import com.yin.ai.platform.agent.event.AgentEvent;
import reactor.core.publisher.Flux;
import com.yin.ai.platform.core.agent.mode.AgentMode;

/**
 * Agent 执行模式 SPI — 每种 AgentMode 对应一个实现。
 * <p>
 * 设计原则：
 * - Pattern 只负责"怎么执行"，不负责"何时执行"（由 AgentRouter 决定）
 * - Pattern 不负责"执行状态管理"（由 TaskEngine 负责）
 * - Pattern 通过 AgentExecutionContext 读写共享状态
 * - Pattern 通过 ctx.getEventPublisher() 发射事件
 * - Pattern 之间不互相调用，通过 Context 共享数据
 * <p>
 * 通过 Spring Bean 自动发现，AgentRuntime 按 mode() 匹配。
 */
public interface AgentPattern {

    /** 该 Pattern 对应的模式 */
    AgentMode mode();

    /**
     * 流式执行。返回事件流，由 AgentRuntime 桥接到 SSE。
     * <p>
     * 实现约定：
     * - 正常完成：Flux.onComplete()
     * - 执行失败：Flux.onError(e)
     * - 每步完成时发射 STEP_COMPLETED 事件
     * - 产物生成时发射 ARTIFACT_CREATED 事件
     *
     * @param ctx 共享执行上下文
     * @return 事件流
     */
    Flux<AgentEvent> execute(AgentExecutionContext ctx);

    /**
     * 是否支持恢复执行（中断后从断点继续）。
     * SIMPLE 和 REACT 不支持（无状态或状态不可序列化），
     * PLAN_EXECUTE 和 DEEP_RESEARCH 支持。
     */
    default boolean resumable() {
        return false;
    }

    /**
     * 优先级，数值越小越优先（用于同 mode 多实现时的选择）。
     * 默认 100，业务层可通过更低优先级覆盖平台默认实现。
     */
    default int priority() {
        return 100;
    }
}
