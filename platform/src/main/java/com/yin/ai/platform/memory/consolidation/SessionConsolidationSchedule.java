package com.yin.ai.platform.memory.consolidation;

import java.time.Instant;

/** Schedules session consolidation without coupling the platform to Redis scheduling details. */
public interface SessionConsolidationSchedule {
    void schedule(String sessionId, Instant dueAt);
    void scheduleNow(String sessionId);
}
