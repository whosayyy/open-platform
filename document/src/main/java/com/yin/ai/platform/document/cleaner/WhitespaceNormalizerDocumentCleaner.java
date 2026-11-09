package com.yin.ai.platform.document.cleaner;

import com.yin.ai.platform.document.model.ParsedDocument;

/**
 * 空白规范化清洗器 — 连续空行压缩、行尾空白去除、换行符统一。
 */
public class WhitespaceNormalizerDocumentCleaner implements DocumentCleaner {

    @Override
    public int priority() {
        return 10;
    }

    @Override
    public void clean(ParsedDocument document) {
        if (document.getContent() == null || document.getContent().isEmpty()) {
            return;
        }

        String content = document.getContent();

        // 标准化换行符
        content = content.replace("\r\n", "\n").replace("\r", "\n");

        // 压缩连续空行
        StringBuilder sb = new StringBuilder();
        int blankLines = 0;
        // 按换行分割文本，limit=-1 保留末尾空行不丢弃
        for (String line : content.split("\n", -1)) {
            // 仅移除每行末尾空白（行首空格保留）
            String stripped = line.stripTrailing();
            if (stripped.isEmpty()) {
                blankLines++;
                // 空行计数≤2才追加换行，超过2的空行直接丢弃
                if (blankLines <= 2) {
                    sb.append('\n');
                }
            } else {
                blankLines = 0;
                sb.append(stripped).append('\n');
            }
        }

        // 移除末尾空白行
        String result = sb.toString().stripTrailing();
        if (!result.isEmpty()) {
            result += '\n';
        }

        document.setContent(result);
        document.setContentLength(result.length());
    }
}
