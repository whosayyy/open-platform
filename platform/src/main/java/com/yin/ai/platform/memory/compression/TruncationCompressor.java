package com.yin.ai.platform.memory.compression;

import com.yin.ai.platform.memory.TokenCounter;
import com.yin.ai.platform.core.memory.MemoryCell;
import com.yin.ai.platform.core.memory.MemoryType;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Token 预算截断压缩器 — 当 Cell 列表的 Token 总数超过预算时，丢弃最早的 Cell。
 * <p>
 * 截断策略：从尾部开始保留 Cell，直到累计 Token 数达到预算上限。
 * 单条 Cell 超预算时保留该 Cell（不能返回空上下文）。
 * <p>
 * 与 {@link SlidingWindowTruncator} 的区别：
 * <ul>
 *   <li>本压缩器操作通用 {@link MemoryCell}，不感知 user/assistant 角色</li>
 *   <li>SlidingWindowTruncator 操作 ChatMessage，以对话轮次为最小截断单位</li>
 * </ul>
 */
@Slf4j
public class TruncationCompressor implements MemoryCompressor {

    @Override
    public List<MemoryCell> compress(List<MemoryCell> cells, CompressionContext ctx) {
        if (cells == null || cells.isEmpty()) {
            return List.of();
        }

        int maxTokens = ctx.maxTokens();
        if (maxTokens <= 0) {
            return new ArrayList<>(cells);
        }

        int n = cells.size();

        // 预计算 Token 数
        int[] tokenCounts = new int[n];
        int totalTokens = 0;
        for (int i = 0; i < n; i++) {
            tokenCounts[i] = TokenCounter.estimate(cells.get(i).content());
            totalTokens += tokenCounts[i];
        }

        if (totalTokens <= maxTokens) {
            return new ArrayList<>(cells);
        }

        // 从尾部开始保留
        List<MemoryCell> result = new ArrayList<>();
        int accumulated = 0;

        for (int i = n - 1; i >= 0; i--) {
            int tokens = tokenCounts[i];
            if (accumulated + tokens > maxTokens) {
                if (result.isEmpty()) {
                    log.warn("[TRUNCATE] single cell exceeds budget: tokens={} maxTokens={}",
                            tokens, maxTokens);
                    // 将末尾的cell 加入结果
                    result.add(cells.get(i));
                }
                break;
            }
            result.add(cells.get(i));
            accumulated += tokens;
        }

        // 反转结果，回复时间序性
        Collections.reverse(result);

        int removed = n - result.size();
        if (removed > 0) {
            log.info("[TRUNCATE] removed {} cells, kept {} cells, tokens={}/{}",
                    removed, result.size(), accumulated, maxTokens);
        }

        return result;
    }

    @Override
    public CompressionStrategy strategy() {
        return CompressionStrategy.TRUNCATE;
    }
}
