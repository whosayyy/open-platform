package com.yin.ai.platform.memory.consolidation;

import com.yin.ai.platform.memory.episodic.Episode;
import com.yin.ai.platform.memory.orchestration.MemoryAccessContext;

public interface MemoryEpisodePort {
    String save(Episode episode, double importance, MemoryAccessContext context);
}
