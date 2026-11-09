package com.yin.ai.platform.runtime.config;

import com.yin.ai.platform.runtime.postprocess.MarkdownFormatProcessor;
import com.yin.ai.platform.runtime.postprocess.OutputTruncateProcessor;
import com.yin.ai.platform.runtime.postprocess.ResponsePostProcessor;
import com.yin.ai.platform.runtime.postprocess.ResponsePostProcessorChain;
import com.yin.ai.platform.runtime.postprocess.SensitiveWordResponseFilter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Response 后处理自动配置。
 * <p>
 * 支持通过 {@code ai.runtime.postprocess.sensitive-words-enabled} 等属性控制各处理器开关。
 * 业务模块可通过注册同类型 Bean 覆盖默认实现。
 */
@Configuration
public class ResponsePostProcessorConfig {

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(value = "ai.runtime.postprocess.sensitive-words-enabled", havingValue = "true", matchIfMissing = true)
    public SensitiveWordResponseFilter sensitiveWordResponseFilter() {
        return new SensitiveWordResponseFilter();
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(value = "ai.runtime.postprocess.markdown-format-enabled", havingValue = "true", matchIfMissing = true)
    public MarkdownFormatProcessor markdownFormatProcessor() {
        return new MarkdownFormatProcessor();
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(value = "ai.runtime.postprocess.output-truncate-enabled", havingValue = "true", matchIfMissing = true)
    public OutputTruncateProcessor outputTruncateProcessor() {
        return new OutputTruncateProcessor();
    }

    @Bean
    @ConditionalOnMissingBean
    public ResponsePostProcessorChain responsePostProcessorChain(List<ResponsePostProcessor> processors) {
        return new ResponsePostProcessorChain(processors);
    }
}
