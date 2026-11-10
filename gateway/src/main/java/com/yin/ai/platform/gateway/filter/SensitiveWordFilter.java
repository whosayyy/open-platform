package com.yin.ai.platform.gateway.filter;

import com.yin.ai.platform.filter.DfaMatcher;
import com.yin.ai.platform.filter.SensitiveWordProvider;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 输入端敏感词过滤器（DFA 算法实现）。
 * <p>
 * 检查用户输入是否包含敏感词，若包含则中断 AI 回复。
 * 词库由 {@link SensitiveWordProvider} 提供，默认从 yaml 配置读取。
 */
@Slf4j
@Component
public class SensitiveWordFilter {

    private final SensitiveWordProvider wordProvider;
    private volatile DfaMatcher matcher;

    public SensitiveWordFilter(SensitiveWordProvider wordProvider) {
        this.wordProvider = wordProvider;
    }

    @PostConstruct
    public void init() {
        this.matcher = new DfaMatcher(wordProvider.getSensitiveWords());
        log.info("敏感词过滤器初始化完成，共加载 {} 个敏感词", matcher.wordCount());
    }

    public boolean containsSensitiveWords(String text) {
        if (text == null || text.isBlank()) return false;
        return matcher.contains(text);
    }

    public String sanitize(String text) {
        if (text == null || text.isBlank()) return text;
        return matcher.sanitize(text, '*');
    }
}
