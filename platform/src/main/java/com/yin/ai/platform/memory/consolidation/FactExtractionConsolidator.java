package com.yin.ai.platform.memory.consolidation;

import com.yin.ai.platform.core.memory.MemoryCell;
import com.yin.ai.platform.core.memory.MemoryId;
import com.yin.ai.platform.core.memory.semantic.Fact;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fact 提取 Consolidator — 基于规则从 Cell 内容中提取简单事实。
 * <p>
 * 当前支持的提取模式：
 * <ul>
 *   <li>"X 是 Y" / "X is Y" → Fact(X, 是, Y)</li>
 *   <li>"X 喜欢 Y" / "X likes Y" → Fact(X, 喜欢, Y)</li>
 *   <li>"X 在 Y" / "X at Y" → Fact(X, 位于, Y)</li>
 * </ul>
 * <p>
 * 注意：此为规则引擎骨架，生产环境应使用 LLM 驱动的 Fact Extraction
 * （通过 {@link com.yin.ai.platform.core.memory.semantic.EmbeddingService} 生成向量嵌入）。
 */
@Slf4j
public class FactExtractionConsolidator implements MemoryConsolidator {

    // 中英文模式
    private static final List<Pattern> FACT_PATTERNS = List.of(
            Pattern.compile("(.+?)\\s*是\\s*(.+)"),
            Pattern.compile("(.+?)\\s+is\\s+(.+)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(.+?)\\s*喜欢\\s*(.+)"),
            Pattern.compile("(.+?)\\s+likes\\s+(.+)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(.+?)\\s*位于\\s*(.+)"),
            Pattern.compile("(.+?)\\s+at\\s+(.+)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(.+?)\\s*知道\\s*(.+)"),
            Pattern.compile("(.+?)\\s+knows\\s+(.+)", Pattern.CASE_INSENSITIVE)
    );

    @Override
    public ConsolidationResult consolidate(MemoryId memoryId, List<MemoryCell> cells) {
        if (cells == null || cells.isEmpty()) {
            return ConsolidationResult.empty();
        }

        List<Fact> facts = new ArrayList<>();
        List<String> processedCellIds = new ArrayList<>();

        for (MemoryCell cell : cells) {
            List<Fact> cellFacts = extractFacts(cell);
            if (!cellFacts.isEmpty()) {
                facts.addAll(cellFacts);
                processedCellIds.add(cell.cellId().id());
            }
        }

        log.debug("[CONSOLIDATE] extracted {} facts from {} cells", facts.size(), cells.size());
        return new ConsolidationResult(List.of(), facts, List.of(),
                processedCellIds, java.util.Map.of("extractor", "rule-based"));
    }

    private List<Fact> extractFacts(MemoryCell cell) {
        List<Fact> facts = new ArrayList<>();
        String text = cell.content();

        for (Pattern pattern : FACT_PATTERNS) {
            Matcher matcher = pattern.matcher(text);
            while (matcher.find()) {
                String subject = matcher.group(1).trim();
                String predicate = determinePredicate(pattern.pattern());
                String object = matcher.group(2).trim();

                if (subject.length() >= 2 && object.length() >= 1) {
                    facts.add(Fact.of("fact-" + UUID.randomUUID(), subject, predicate, object, 0.6)
                            .withSourceCell(cell.cellId().id()));
                }
            }
        }

        return facts;
    }

    private String determinePredicate(String regex) {
        if (regex.contains("是") || regex.contains("is")) return "是";
        if (regex.contains("喜欢") || regex.contains("likes")) return "喜欢";
        if (regex.contains("位于") || regex.contains("at")) return "位于";
        if (regex.contains("知道") || regex.contains("knows")) return "知道";
        return "关联";
    }
}
