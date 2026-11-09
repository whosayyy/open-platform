package com.yin.ai.platform.runtime;

import com.yin.ai.platform.gateway.stream.StreamPacket;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * Skill Runtime — 通用 Skill 执行引擎（SPI）。
 * <p>
 * V2.1 升级：支持多 Skill 并发执行，通过 PromptComposer 动态组合 System Prompt。
 * 单 Skill 方法保留向后兼容，内部委托到多 Skill 方法。
 * <p>
 * 不含任何业务特定逻辑，所有业务差异由 .md Skill 文件 + SkillSelector 表达。
 */
public interface SkillRuntime {

    // ——— 多 Skill 执行（V2.1 主入口） ———

    /**
     * 阻塞式执行多个 Skill。
     *
     * @param skillNames 按优先级排序的 Skill 名称列表
     * @param ctx        执行上下文
     * @return 执行结果
     */
    SkillResult execute(List<String> skillNames, SkillExecutionContext ctx);

    /**
     * 流式执行多个 Skill。
     *
     * @param skillNames 按优先级排序的 Skill 名称列表
     * @param ctx        执行上下文
     * @return SSE 事件流
     */
    Flux<StreamPacket> executeStream(List<String> skillNames, SkillExecutionContext ctx);

    // ——— 单 Skill 执行（向后兼容） ———

    /**
     * 阻塞式执行单个 Skill。
     *
     * @deprecated 使用 {@link #execute(List, SkillExecutionContext)} 替代
     * @param skillName Skill 名称
     * @param ctx       执行上下文
     * @return 执行结果
     */
    @Deprecated
    SkillResult execute(String skillName, SkillExecutionContext ctx);

    /**
     * 流式执行单个 Skill。
     *
     * @deprecated 使用 {@link #executeStream(List, SkillExecutionContext)} 替代
     * @param skillName Skill 名称
     * @param ctx       执行上下文
     * @return SSE 事件流
     */
    @Deprecated
    Flux<StreamPacket> executeStream(String skillName, SkillExecutionContext ctx);

    /**
     * 取消正在执行的流式任务。
     */
    void cancel(String taskId);
}
