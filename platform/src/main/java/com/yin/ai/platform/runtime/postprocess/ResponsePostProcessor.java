package com.yin.ai.platform.runtime.postprocess;

import com.yin.ai.platform.runtime.SkillExecutionContext;

/**
 * Response 后处理器 SPI — 在 AI Gateway 返回后、SkillResult 组装前对响应内容进行处理。
 * <p>
 * 实现类通过 Spring Bean 自动注册到 {@link ResponsePostProcessorChain}，
 * 按 {@link #order()} 升序执行。后处理失败时应降级返回原始内容，不阻塞用户。
 */
public interface ResponsePostProcessor {

    /**
     * 对 LLM 原始响应进行后处理。
     *
     * @param rawResponse Gateway 返回的原始内容
     * @param ctx         执行上下文
     * @return 处理后的内容
     */
    String process(String rawResponse, SkillExecutionContext ctx);

    /** 执行顺序，数值越小越先执行 */
    int order();
}
