package com.yin.ai.platform.memory.episodic;

import com.yin.ai.platform.core.memory.MemoryCell;
import com.yin.ai.platform.core.memory.MemoryId;
import com.yin.ai.platform.core.memory.MemoryType;

import java.util.List;

/**
 * Episode 提取器 SPI — 从 Timeline 中识别并切分 Episode。
 * <p>
 * 实现示例：
 * <ul>
 *   <li>基于时间间隔的切分（相邻 Cell 间隔超过阈值 → 新 Episode）</li>
 *   <li>基于语义边界的切分（LLM 识别话题转换点）</li>
 *   <li>基于 Task/Workflow 边界的切分（PlanNode 生命周期事件）</li>
 * </ul>
 */
@FunctionalInterface
public interface EpisodeExtractor {

    /**
     * 从有序 Cell 列表中提取 Episode。
     *
     * @param memoryId 所属 Memory
     * @param cells    按时间排序的 Cell 列表
     * @return 提取的 Episode 列表（按时间排序）
     */
    List<Episode> extract(MemoryId memoryId, List<MemoryCell> cells);

    /** 该提取器支持的 Memory 类型 */
    default boolean supports(MemoryType type) {
        return type == MemoryType.EPISODIC || type == MemoryType.SESSION;
    }
}
