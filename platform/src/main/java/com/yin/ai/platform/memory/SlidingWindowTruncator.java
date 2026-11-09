package com.yin.ai.platform.memory;

import com.yin.ai.platform.core.memory.MemoryCell;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 滑动窗口截断器 — 当对话历史 Token 超过预算时，丢弃最早的对话轮次。
 * <p>
 * 保留策略：以对话轮次（user + assistant 对）为最小截断单位，从最早的消息开始丢弃。
 * 末尾未配对的非完整轮次（如最后一条 user 消息尚未收到 assistant 回复）也会被保留。
 * <p>
 * 提供两套 API：
 * <ul>
 *   <li>{@link #truncate(List, int)} — ChatMessage 版本，保持向后兼容</li>
 *   <li>{@link #truncateCells(List, int)} — MemoryCell 版本，通过 "role" attribute 识别角色</li>
 * </ul>
 * <p>
 * 注意：System prompt 由调用方在历史消息之前单独添加，不在本截断器的处理范围内。
 */
@Slf4j
public class SlidingWindowTruncator {

    private SlidingWindowTruncator() {}

    // ==================== ChatMessage API (backward compatible) ====================

    /**
     * 按 Token 预算截断消息列表，以对话轮次（user+assistant 对）为单位。
     *
     * @param messages 原始消息列表（按时间顺序，role 为 "user" 或 "assistant"）
     * @param maxTokens Token 预算上限
     * @return 截断后的消息列表（按时间顺序，保持对话轮次完整）
     */
    public static List<SessionMemoryManager.ChatMessage> truncate(
            List<SessionMemoryManager.ChatMessage> messages, int maxTokens) {

        if (messages == null || messages.isEmpty()) {
            return List.of();
        }

        int n = messages.size();

        int[] tokenCounts = new int[n];
        int totalTokens = 0;
        for (int i = 0; i < n; i++) {
            tokenCounts[i] = TokenCounter.estimate(messages.get(i).content());
            totalTokens += tokenCounts[i];
        }

        if (totalTokens <= maxTokens) {
            return new ArrayList<>(messages);
        }

        List<SessionMemoryManager.ChatMessage> result = new ArrayList<>();
        int accumulated = 0;
        int keptTurns = 0;
        int i = n - 1;

        while (i >= 0) {
            SessionMemoryManager.ChatMessage current = messages.get(i);

            if ("assistant".equals(current.role()) && i > 0
                    && "user".equals(messages.get(i - 1).role())) {
                int turnTokens = tokenCounts[i - 1] + tokenCounts[i];
                if (accumulated + turnTokens > maxTokens) {
                    if (result.isEmpty()) {
                        log.warn("[TRUNCATOR] single turn exceeds budget: turnTokens={} maxTokens={}",
                                turnTokens, maxTokens);
                    } else {
                        break;
                    }
                }
                result.add(current);
                result.add(messages.get(i - 1));
                accumulated += turnTokens;
                keptTurns++;
                i -= 2;
            } else {
                int tokens = tokenCounts[i];
                if (accumulated + tokens > maxTokens) {
                    if (result.isEmpty()) {
                        log.warn("[TRUNCATOR] single message exceeds budget: tokens={} maxTokens={}",
                                tokens, maxTokens);
                    } else {
                        break;
                    }
                }
                result.add(current);
                accumulated += tokens;
                i--;
            }
        }

        Collections.reverse(result);

        int removed = n - result.size();
        if (removed > 0) {
            log.info("[TRUNCATOR] removed {} msgs (~{} turns), kept {} msgs ({} turns), "
                            + "tokens={}/{}",
                    removed, removed / 2, result.size(), keptTurns,
                    accumulated, maxTokens);
        }

        return result;
    }

    // ==================== MemoryCell API (new — Role-aware) ====================

    /**
     * 按 Token 预算截断 MemoryCell 列表，以对话轮次为单位。
     * <p>
     * 通过 {@code cell.attributes().get("role")} 识别 user/assistant 角色，
     * 与 ChatMessage 版本保持相同的轮次完整性语义。
     * 无 role 属性的 Cell 被视为单条处理（不参与轮次配对）。
     *
     * @param cells     原始 Cell 列表（按时间排序）
     * @param maxTokens Token 预算上限
     * @return 截断后的 Cell 列表（按时间顺序）
     */
    public static List<MemoryCell> truncateCells(List<MemoryCell> cells, int maxTokens) {
        if (cells == null || cells.isEmpty()) {
            return List.of();
        }

        int n = cells.size();

        int[] tokenCounts = new int[n];
        int totalTokens = 0;
        for (int i = 0; i < n; i++) {
            tokenCounts[i] = TokenCounter.estimate(cells.get(i).content());
            totalTokens += tokenCounts[i];
        }

        if (totalTokens <= maxTokens) {
            return new ArrayList<>(cells);
        }

        List<MemoryCell> result = new ArrayList<>();
        int accumulated = 0;
        int i = n - 1;

        while (i >= 0) {
            MemoryCell current = cells.get(i);
            String currentRole = roleOf(current);

            if ("assistant".equals(currentRole) && i > 0
                    && "user".equals(roleOf(cells.get(i - 1)))) {
                int turnTokens = tokenCounts[i - 1] + tokenCounts[i];
                if (accumulated + turnTokens > maxTokens) {
                    if (result.isEmpty()) {
                        log.warn("[TRUNCATOR] single turn exceeds budget: turnTokens={} maxTokens={}",
                                turnTokens, maxTokens);
                    } else {
                        break;
                    }
                }
                result.add(current);
                result.add(cells.get(i - 1));
                accumulated += turnTokens;
                i -= 2;
            } else {
                int tokens = tokenCounts[i];
                if (accumulated + tokens > maxTokens) {
                    if (result.isEmpty()) {
                        result.add(current);
                    }
                    break;
                }
                result.add(current);
                accumulated += tokens;
                i--;
            }
        }

        Collections.reverse(result);

        int removed = n - result.size();
        if (removed > 0) {
            log.info("[TRUNCATOR] removed {} cells, kept {} cells, tokens={}/{}",
                    removed, result.size(), accumulated, maxTokens);
        }

        return result;
    }

    private static String roleOf(MemoryCell cell) {
        Object role = cell.attributes().get("role");
        return role != null ? role.toString() : "user";
    }
}
