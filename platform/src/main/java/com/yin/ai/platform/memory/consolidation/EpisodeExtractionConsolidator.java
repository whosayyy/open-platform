package com.yin.ai.platform.memory.consolidation;

import com.yin.ai.platform.core.memory.MemoryCell;
import com.yin.ai.platform.core.memory.MemoryId;
import com.yin.ai.platform.memory.episodic.Episode;
import com.yin.ai.platform.memory.episodic.EpisodeType;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Episode 提取 Consolidator — 基于时间间隔将 Timeline 切分为 Episode。
 * <p>
 * 切分规则：
 * <ol>
 *   <li>相邻 Cell 时间间隔超过 {@code gapThreshold} → 开始新 Episode</li>
 *   <li>Cell 数量超过 {@code maxCellsPerEpisode} → 强制切分</li>
 *   <li>Episode 标题取第一个 Cell 的前 80 字符</li>
 * </ol>
 * <p>
 * 未来扩展：语义边界检测（LLM 识别话题转换点），替换纯时间间隔策略。
 */
@Slf4j
public class EpisodeExtractionConsolidator implements MemoryConsolidator {

    private final Duration gapThreshold;
    private final int maxCellsPerEpisode;

    public EpisodeExtractionConsolidator() {
        this(Duration.ofMinutes(30), 50);
    }

    public EpisodeExtractionConsolidator(Duration gapThreshold, int maxCellsPerEpisode) {
        this.gapThreshold = gapThreshold;
        this.maxCellsPerEpisode = maxCellsPerEpisode;
    }

    @Override
    public ConsolidationResult consolidate(MemoryId memoryId, List<MemoryCell> cells) {
        if (cells == null || cells.isEmpty()) {
            return ConsolidationResult.empty();
        }

        List<Episode> episodes = new ArrayList<>();
        List<String> processedCellIds = new ArrayList<>();

        List<MemoryCell> currentBatch = new ArrayList<>();
        Instant batchStart = cells.get(0).timestamp();

        for (int i = 0; i < cells.size(); i++) {
            MemoryCell cell = cells.get(i);

            if (shouldSplit(currentBatch, cell, batchStart)) {
                episodes.add(buildEpisode(memoryId, currentBatch, batchStart, processedCellIds));
                currentBatch = new ArrayList<>();
                batchStart = cell.timestamp();
            }

            currentBatch.add(cell);
            processedCellIds.add(cell.cellId().id());
        }

        // 最后一个 batch
        if (!currentBatch.isEmpty()) {
            episodes.add(buildEpisode(memoryId, currentBatch, batchStart, processedCellIds));
        }

        log.debug("[CONSOLIDATE] extracted {} episodes from {} cells", episodes.size(), cells.size());
        return new ConsolidationResult(episodes, List.of(), List.of(),
                processedCellIds, java.util.Map.of("gapThreshold", gapThreshold.toString()));
    }

    private boolean shouldSplit(List<MemoryCell> batch, MemoryCell nextCell, Instant batchStart) {
        if (batch.isEmpty()) {
            return false;
        }
        // 数量超限
        if (batch.size() >= maxCellsPerEpisode) {
            return true;
        }
        // 时间间隔超限
        MemoryCell last = batch.get(batch.size() - 1);
        Duration gap = Duration.between(last.timestamp(), nextCell.timestamp());
        return gap.abs().compareTo(gapThreshold) > 0;
    }

    private Episode buildEpisode(MemoryId memoryId, List<MemoryCell> cells,
                                  Instant batchStart, List<String> processedIds) {
        String title = cells.get(0).content();
        if (title.length() > 80) {
            title = title.substring(0, 80) + "...";
        }
        List<String> cellIds = cells.stream().map(c -> c.cellId().id()).toList();
        Instant spanFrom = cells.get(0).timestamp();
        Instant spanTo = cells.get(cells.size() - 1).timestamp();

        return Episode.of("ep-" + UUID.randomUUID(), memoryId, title,
                EpisodeType.CONVERSATION, spanFrom, spanTo, cellIds);
    }
}
