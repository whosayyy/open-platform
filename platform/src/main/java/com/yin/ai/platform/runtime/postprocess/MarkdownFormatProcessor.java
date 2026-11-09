package com.yin.ai.platform.runtime.postprocess;

import com.yin.ai.platform.runtime.SkillExecutionContext;
import lombok.extern.slf4j.Slf4j;

import java.util.regex.Pattern;

/**
 * Markdown 格式化处理器 — 修正 LLM 输出中常见的格式问题：
 * <ul>
 *   <li>移除孤立的无效 heading（如单独的 # 号）</li>
 *   <li>确保 heading 后有空行</li>
 *   <li>统一连续换行为两个换行</li>
 * </ul>
 */
@Slf4j
public class MarkdownFormatProcessor implements ResponsePostProcessor {

    private static final Pattern LONELY_HASH = Pattern.compile("^#{1,2}\\s*$", Pattern.MULTILINE);
    private static final Pattern HEADING_NO_BLANK = Pattern.compile("(^#{1,6}\\s.+$)(\n)([^#\n])", Pattern.MULTILINE);
    private static final Pattern EXCESSIVE_NEWLINES = Pattern.compile("\n{3,}");

    @Override
    public String process(String rawResponse, SkillExecutionContext ctx) {
        if (rawResponse == null || rawResponse.isBlank()) return rawResponse;

        String result = rawResponse;

        result = LONELY_HASH.matcher(result).replaceAll("");
        result = HEADING_NO_BLANK.matcher(result).replaceAll("$1\n\n$3");
        result = EXCESSIVE_NEWLINES.matcher(result).replaceAll("\n\n");

        if (!result.equals(rawResponse)) {
            log.debug("MarkdownFormatProcessor applied corrections");
        }
        return result;
    }

    @Override
    public int order() {
        return 20;
    }
}
