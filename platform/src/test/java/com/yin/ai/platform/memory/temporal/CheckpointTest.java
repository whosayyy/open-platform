package com.yin.ai.platform.memory.temporal;

import com.yin.ai.platform.core.memory.MemoryId;
import com.yin.ai.platform.core.memory.MemoryType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Checkpoint 快照")
class CheckpointTest {

    private MemoryId memoryId;
    private TimelinePosition position;

    @BeforeEach
    void setUp() {
        memoryId = new MemoryId("mem-1", MemoryType.WORKING, "owner-1");
        position = new TimelinePosition(42, "stream-1");
    }

    @Test
    @DisplayName("FULL Checkpoint 包含完整状态")
    void shouldCreateFullCheckpoint() {
        Map<String, byte[]> state = Map.of("mem-1", "serialized-state".getBytes(StandardCharsets.UTF_8));
        Checkpoint ckpt = Checkpoint.full(memoryId, position, state);

        assertNotNull(ckpt.id());
        assertEquals(memoryId, ckpt.memoryId());
        assertEquals(CheckpointType.FULL, ckpt.type());
        assertEquals(position, ckpt.timelinePosition());
        assertArrayEquals("serialized-state".getBytes(StandardCharsets.UTF_8), ckpt.state().get("mem-1"));
        assertNotNull(ckpt.createdAt());
    }

    @Test
    @DisplayName("INCREMENTAL Checkpoint 记录 baseCheckpointId")
    void shouldCreateIncrementalCheckpoint() {
        Map<String, byte[]> state = Map.of("mem-1", "delta".getBytes(StandardCharsets.UTF_8));
        Checkpoint ckpt = Checkpoint.incremental(memoryId, position, state, "base-ckpt-1");

        assertEquals(CheckpointType.INCREMENTAL, ckpt.type());
        assertEquals("base-ckpt-1", ckpt.metadata().get("baseCheckpointId"));
    }

    @Test
    @DisplayName("空 state 和 metadata 自动填充为空 Map")
    void shouldHandleNullStateAndMetadata() {
        Checkpoint ckpt = new Checkpoint("id", memoryId, CheckpointType.FULL,
                position, null, Instant.now(), null);

        assertNotNull(ckpt.state());
        assertTrue(ckpt.state().isEmpty());
        assertNotNull(ckpt.metadata());
        assertTrue(ckpt.metadata().isEmpty());
    }

    @Test
    @DisplayName("TimelinePosition 支持比较")
    void shouldCompareTimelinePositions() {
        TimelinePosition pos1 = new TimelinePosition(10, "s1");
        TimelinePosition pos2 = new TimelinePosition(20, "s1");

        assertTrue(pos2.isAfter(pos1));
        assertTrue(pos1.isBefore(pos2));
        assertFalse(pos1.isAfter(pos2));
        assertFalse(pos1.isAfter(pos1));
    }

    @Test
    @DisplayName("TimelinePosition.origin 返回零偏移")
    void shouldCreateOriginPosition() {
        TimelinePosition origin = TimelinePosition.origin("test-stream");
        assertEquals(0, origin.offset());
        assertEquals("test-stream", origin.streamId());
    }
}
