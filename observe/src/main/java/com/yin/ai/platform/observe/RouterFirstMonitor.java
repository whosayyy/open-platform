package com.yin.ai.platform.observe;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;

/** Fifteen-minute Router-first window shared by Skill and Agent runtime entry points. */
@Component
public class RouterFirstMonitor {

    public static final Duration WINDOW = Duration.ofMinutes(15);
    public static final int MIN_REQUESTS = 100;
    public static final double MAX_AGENT_RATIO = 0.20;

    private final MeterRegistry meters;
    private final Clock clock;
    private final Deque<Bucket> buckets = new ArrayDeque<>();
    private long lastAlertMinute = Long.MIN_VALUE;

    public RouterFirstMonitor(MeterRegistry meters) {
        this(meters, Clock.systemUTC());
    }

    public RouterFirstMonitor(MeterRegistry meters, Clock clock) {
        this.meters = meters;
        this.clock = clock;
        meters.gauge("ai.router.path.window.ratio", java.util.List.of(
                io.micrometer.core.instrument.Tag.of("path", "agent")), this,
                monitor -> monitor.snapshot().agentRatio());
        meters.gauge("ai.router.path.window.requests", java.util.List.of(
                io.micrometer.core.instrument.Tag.of("path", "all")), this,
                monitor -> monitor.snapshot().total());
    }

    public void recordAgent() {
        record(true);
    }

    public void recordSkill() {
        record(false);
    }

    public synchronized Snapshot snapshot() {
        prune(clock.instant());
        long total = buckets.stream().mapToLong(bucket -> bucket.total).sum();
        long agent = buckets.stream().mapToLong(bucket -> bucket.agent).sum();
        double ratio = total == 0 ? 0.0 : (double) agent / total;
        return new Snapshot(total, agent, ratio, total >= MIN_REQUESTS && ratio > MAX_AGENT_RATIO);
    }

    private synchronized void record(boolean agentPath) {
        Instant now = clock.instant();
        prune(now);
        long minute = now.getEpochSecond() / 60;
        Bucket bucket = buckets.peekLast();
        if (bucket == null || bucket.minute != minute) {
            bucket = new Bucket(minute);
            buckets.addLast(bucket);
        }
        bucket.total++;
        if (agentPath) bucket.agent++;
        meters.counter("ai.router.path.requests", "path", agentPath ? "agent" : "skill").increment();

        Snapshot snapshot = snapshot();
        if (snapshot.alerting() && lastAlertMinute != minute) {
            lastAlertMinute = minute;
            meters.counter("ai.router.path.alerts", "reason", "agent_ratio_high").increment();
        }
    }

    private void prune(Instant now) {
        long firstIncludedMinute = (now.minus(WINDOW).getEpochSecond() / 60) + 1;
        while (!buckets.isEmpty() && buckets.peekFirst().minute < firstIncludedMinute) {
            buckets.removeFirst();
        }
    }

    public record Snapshot(long total, long agent, double agentRatio, boolean alerting) {}

    private static final class Bucket {
        private final long minute;
        private long total;
        private long agent;

        private Bucket(long minute) {
            this.minute = minute;
        }
    }
}
