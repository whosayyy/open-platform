package com.yin.ai.platform.memory.multiagent;

import com.yin.ai.platform.core.memory.MemoryId;
import com.yin.ai.platform.core.memory.MemoryType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("SharedMemory 权限模型")
class SharedMemoryTest {

    private SharedMemory exclusiveMemory;
    private SharedMemory sharedReadMemory;
    private SharedMemory sharedWriteMemory;
    private SharedMemory publicMemory;

    @BeforeEach
    void setUp() {
        MemoryId id = MemoryId.of("shared-1", MemoryType.EPISODIC, "user-1");
        exclusiveMemory = SharedMemory.exclusive(id, "agent-A");
        sharedReadMemory = exclusiveMemory.withOwnership(OwnershipLevel.SHARED_READ)
                .shareWith("agent-B");
        sharedWriteMemory = exclusiveMemory.withOwnership(OwnershipLevel.SHARED_WRITE)
                .shareWith("agent-B");
        publicMemory = exclusiveMemory.withOwnership(OwnershipLevel.PUBLIC);
    }

    @Test
    @DisplayName("EXCLUSIVE: 仅 owner 可读写")
    void exclusiveOnlyOwner() {
        assertThat(exclusiveMemory.canRead("agent-A")).isTrue();
        assertThat(exclusiveMemory.canWrite("agent-A")).isTrue();
        assertThat(exclusiveMemory.canRead("agent-B")).isFalse();
        assertThat(exclusiveMemory.canWrite("agent-B")).isFalse();
    }

    @Test
    @DisplayName("SHARED_READ: owner 可写，授权者可读")
    void sharedReadPermissions() {
        assertThat(sharedReadMemory.canRead("agent-A")).isTrue();
        assertThat(sharedReadMemory.canWrite("agent-A")).isTrue();
        assertThat(sharedReadMemory.canRead("agent-B")).isTrue(); // 授权读取
        assertThat(sharedReadMemory.canWrite("agent-B")).isFalse(); // 不可写
        assertThat(sharedReadMemory.canRead("agent-C")).isFalse(); // 未授权
    }

    @Test
    @DisplayName("SHARED_WRITE: 所有授权者可读写")
    void sharedWritePermissions() {
        assertThat(sharedWriteMemory.canWrite("agent-A")).isTrue();
        assertThat(sharedWriteMemory.canWrite("agent-B")).isTrue();
        assertThat(sharedWriteMemory.canWrite("agent-C")).isTrue(); // SHARED_WRITE → all can write
    }

    @Test
    @DisplayName("PUBLIC: 任意 agent 可读写")
    void publicPermissions() {
        assertThat(publicMemory.canRead("anyone")).isTrue();
        assertThat(publicMemory.canWrite("anyone")).isTrue();
    }

    @Test
    @DisplayName("shareWith 追加授权 agent")
    void shouldShareWith() {
        SharedMemory shared = exclusiveMemory.withOwnership(OwnershipLevel.SHARED_READ)
                .shareWith("agent-B").shareWith("agent-C");

        assertThat(shared.sharedWithAgentIds()).contains("agent-B", "agent-C");
    }

    @Test
    @DisplayName("shareWith 重复添加不变")
    void shouldNotDuplicate() {
        SharedMemory shared = exclusiveMemory.shareWith("agent-B").shareWith("agent-B");
        assertThat(shared.sharedWithAgentIds()).hasSize(1);
    }
}
