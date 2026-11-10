package com.yin.ai.platform.engine.stream;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.LongAdder;

/**
 * 管道运行时指标，用于监控和告警。
 */
@Slf4j
@Component
public class StreamMetrics {
    private final LongAdder totalTasks = new LongAdder();
    private final LongAdder overflowDrops = new LongAdder();
    private final LongAdder clientDisconnects = new LongAdder();
    private final LongAdder toolFailures = new LongAdder();
    private final LongAdder rejectedTasks = new LongAdder();

    public void incrementTotalTasks() { totalTasks.increment(); }
    public void incrementOverflowDrops() { overflowDrops.increment(); }
    public void incrementClientDisconnects() { clientDisconnects.increment(); }
    public void incrementToolFailures() { toolFailures.increment(); }
    public void incrementRejectedTasks() { rejectedTasks.increment(); }

    public long getTotalTasks() { return totalTasks.sum(); }
    public long getOverflowDrops() { return overflowDrops.sum(); }
    public long getClientDisconnects() { return clientDisconnects.sum(); }
    public long getToolFailures() { return toolFailures.sum(); }
    public long getRejectedTasks() { return rejectedTasks.sum(); }

    public void logSnapshot() {
        log.info("Pipeline Metrics | tasks: {} overflow: {} disconnect: {} toolFail: {} rejected: {}",
                totalTasks.sum(), overflowDrops.sum(), clientDisconnects.sum(),
                toolFailures.sum(), rejectedTasks.sum());
    }
}
