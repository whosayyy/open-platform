package com.yin.ai.platform.runtime.postprocess;

import com.yin.ai.platform.runtime.SkillExecutionContext;
import lombok.extern.slf4j.Slf4j;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 输出截断处理器 — 按最大字符数截断 LLM 响应，确保截断在完整句子边界。
 */
@Slf4j
public class OutputTruncateProcessor implements ResponsePostProcessor {

    private static final Pattern SENTENCE_END = Pattern.compile("[。！？\n.!?\n]");

    private final int maxChars;

    public OutputTruncateProcessor() {
        this(4096);
    }

    public OutputTruncateProcessor(int maxChars) {
        this.maxChars = maxChars;
    }

    @Override
    public String process(String rawResponse, SkillExecutionContext ctx) {
        if (rawResponse == null || rawResponse.isBlank()) return rawResponse;
        if (rawResponse.length() <= maxChars) return rawResponse;

        String truncated = rawResponse.substring(0, maxChars);

        Matcher m = SENTENCE_END.matcher(truncated);
        int lastBoundary = -1;
        while (m.find()) {
            lastBoundary = m.end();
        }

        if (lastBoundary > 0) {
            truncated = truncated.substring(0, lastBoundary);
        }

        log.debug("OutputTruncateProcessor truncated {} → {} chars", rawResponse.length(), truncated.length());
        return truncated;
    }

    @Override
    public int order() {
        return 30;
    }
}
