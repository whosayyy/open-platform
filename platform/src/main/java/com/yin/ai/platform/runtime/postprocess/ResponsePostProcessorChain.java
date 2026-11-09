package com.yin.ai.platform.runtime.postprocess;

import com.yin.ai.platform.runtime.SkillExecutionContext;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Response 后处理器链 — 按 {@link ResponsePostProcessor#order()} 排序执行。
 * <p>
 * 单个处理器失败时降级返回原始内容，不阻塞后续处理器和用户响应。
 */
@Slf4j
public class ResponsePostProcessorChain {

    private final List<ResponsePostProcessor> processors;

    public ResponsePostProcessorChain(List<ResponsePostProcessor> processors) {
        if (processors == null || processors.isEmpty()) {
            this.processors = List.of();
        } else {
            List<ResponsePostProcessor> sorted = new ArrayList<>(processors);
            sorted.sort(Comparator.comparingInt(ResponsePostProcessor::order));
            this.processors = List.copyOf(sorted);
        }
        log.info("ResponsePostProcessorChain initialized with {} processors", this.processors.size());
    }

    /**
     * 依次执行所有后处理器。
     *
     * @param rawResponse 原始响应内容
     * @param ctx         执行上下文
     * @return 处理后的内容
     */
    public String process(String rawResponse, SkillExecutionContext ctx) {
        if (rawResponse == null || rawResponse.isBlank()) return rawResponse;
        if (processors.isEmpty()) return rawResponse;

        String result = rawResponse;
        for (ResponsePostProcessor processor : processors) {
            try {
                result = processor.process(result, ctx);
            } catch (Exception e) {
                log.warn("PostProcessor {} failed, using previous result", processor.getClass().getSimpleName(), e);
            }
        }
        return result;
    }

    public int size() {
        return processors.size();
    }
}
