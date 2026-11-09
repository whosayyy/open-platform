package com.yin.ai.platform.filter;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * DFA（Deterministic Finite Automaton）敏感词匹配器。
 * <p>
 * 供输入端 {@link SensitiveWordFilter} 和输出端 SensitiveWordResponseFilter 共用。
 */
public class DfaMatcher {

    private final TrieNode root = new TrieNode();

    public DfaMatcher(Collection<String> words) {
        for (String word : words) {
            addWord(word);
        }
    }

    public boolean contains(String text) {
        if (text == null || text.isBlank()) return false;
        for (int i = 0; i < text.length(); i++) {
            TrieNode cur = root;
            for (int j = i; j < text.length(); j++) {
                char c = text.charAt(j);
                TrieNode next = cur.children.get(c);
                if (next == null) break;
                if (next.isEnd) return true;
                cur = next;
            }
        }
        return false;
    }

    public String sanitize(String text, char maskChar) {
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
                for (int k = i; k <= matchEnd; k++) {
                    sb.setCharAt(k, maskChar);
                }
                i = matchEnd;
            }
        }
        return sb.toString();
    }

    public int wordCount() {
        return countWords(root);
    }

    private int countWords(TrieNode node) {
        int count = node.isEnd ? 1 : 0;
        for (TrieNode child : node.children.values()) {
            count += countWords(child);
        }
        return count;
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
}
