package com.yin.ai.platform.memory.retrieval.pipeline;

import com.yin.ai.platform.core.memory.api.MemoryQuery;
import com.yin.ai.platform.core.memory.api.ScoredMemoryCell;
import com.yin.ai.platform.core.memory.MemoryCell;
import com.yin.ai.platform.core.memory.MemoryType;

import java.util.*;

/**
 * Top-K 选择器 — 支持按 MemoryType 配额和去重。
 * <p>
 * 配置项：
 * <ul>
 *   <li>perTypeQuota: 每种 MemoryType 最多入选 N 条（0 = 不限）</li>
 *   <li>dedupeEnabled: 是否按内容前缀去重（默认开启）</li>
 *   <li>minScore: 低于此分数的 Cell 直接丢弃</li>
 * </ul>
 */
public class TopKSelector implements MemorySelector {

    private final int perTypeQuota;
    private final boolean dedupeEnabled;

    public TopKSelector() {
        this(0, true);
    }

    public TopKSelector(int perTypeQuota, boolean dedupeEnabled) {
        this.perTypeQuota = perTypeQuota;
        this.dedupeEnabled = dedupeEnabled;
    }

    @Override
    public String name() {
        return "topk";
    }

    @Override
    public List<MemoryCell> select(List<ScoredMemoryCell> rankedCells, MemoryQuery query) {
        List<MemoryCell> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Map<MemoryType, Integer> typeCounts = new EnumMap<>(MemoryType.class);

        for (ScoredMemoryCell scored : rankedCells) {
            if (scored.score() < query.minScore()) {
                continue;
            }

            // 配额检查
            if (perTypeQuota > 0) {
                int count = typeCounts.getOrDefault(scored.sourceType(), 0);
                if (count >= perTypeQuota) {
                    continue;
                }
            }

            // 去重（按内容前 100 字符）
            if (dedupeEnabled) {
                String key = dedupeKey(scored.cell().content());
                if (!seen.add(key)) {
                    continue;
                }
            }

            result.add(scored.cell());
            if (perTypeQuota > 0) {
                typeCounts.merge(scored.sourceType(), 1, Integer::sum);
            }

            if (result.size() >= query.topK()) {
                break;
            }
        }
        return result;
    }

    private static String dedupeKey(String content) {
        return content.length() > 100 ? content.substring(0, 100) : content;
    }
}
