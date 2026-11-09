package com.yin.ai.platform.runtime.postprocess;

import com.yin.ai.platform.runtime.SkillExecutionContext;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 输出端敏感词过滤器 — DFA 算法，对 LLM 响应中的敏感词进行脱敏替换。
 * <p>
 * 与输入端过滤（wkc-ai-service SensitiveWordFilter）互补：
 * 输入端拦截恶意输入，输出端兜底防止 LLM 生成违规内容。
 * <p>
 * 默认词库可通过构造参数注入，也可由子类覆盖 {@link #loadWordList()} 扩展。
 */
@Slf4j
public class SensitiveWordResponseFilter implements ResponsePostProcessor {

    private final TrieNode root = new TrieNode();
    private final char maskChar;

    public SensitiveWordResponseFilter() {
        this(DEFAULT_WORDS, '*');
    }

    public SensitiveWordResponseFilter(List<String> wordList, char maskChar) {
        this.maskChar = maskChar;
        for (String word : wordList) {
            addWord(word);
        }
        log.info("SensitiveWordResponseFilter initialized with {} words", wordList.size());
    }

    @Override
    public String process(String rawResponse, SkillExecutionContext ctx) {
        if (rawResponse == null || rawResponse.isBlank()) return rawResponse;
        return sanitize(rawResponse);
    }

    @Override
    public int order() {
        return 10;
    }

    /**
     * 对文本中的敏感词脱敏，替换为 {@code maskChar}。
     */
    //TODO 后期需要引入专业的敏感词库
    public String sanitize(String text) {
        if (text == null || text.isBlank()) return text;

        StringBuilder sb = new StringBuilder(text);
        for (int i = 0; i < sb.length(); i++) {
            TrieNode cur = root;
            int matchEnd = -1;
            for (int j = i; j < sb.length(); j++) {
                char c = sb.charAt(j);
                TrieNode next = cur.children.get(c);
                if (next == null) break;
                if (next.isEnd) matchEnd = j;
                cur = next;
            }
            if (matchEnd >= 0) {
                log.debug("Output sensitive word masked at position {}", i);
                for (int k = i; k <= matchEnd; k++) {
                    sb.setCharAt(k, maskChar);
                }
                i = matchEnd;
            }
        }
        return sb.toString();
    }

    private void addWord(String word) {
        if (word == null || word.isBlank()) return;
        TrieNode cur = root;
        for (int i = 0; i < word.length(); i++) {
            char c = word.charAt(i);
            cur = cur.children.computeIfAbsent(c, k -> new TrieNode());
        }
        cur.isEnd = true;
    }

    private static class TrieNode {
        final Map<Character, TrieNode> children = new HashMap<>();
        boolean isEnd;
    }

    private static final List<String> DEFAULT_WORDS = List.of(
            "法轮功", "六四", "天安门事件", "台独", "藏独", "疆独", "港独",
            "钓鱼岛属于日本", "占中", "色情", "成人片", "裸聊", "一夜情", "援交",
            "杀人", "恐怖袭击", "爆炸物", "枪支弹药",
            "赌博", "毒品", "冰毒", "海洛因", "赌博网站",
            "邪教", "传销"
    );
}
