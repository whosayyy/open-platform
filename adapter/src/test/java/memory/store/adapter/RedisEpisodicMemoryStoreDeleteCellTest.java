package com.yin.ai.platform.memory.store.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yin.ai.platform.adapter.memory.redis.RedisEpisodicMemoryStore;
import com.yin.ai.platform.core.memory.MemoryCellId;
import com.yin.ai.platform.core.memory.MemoryId;
import com.yin.ai.platform.core.memory.MemoryType;
import org.junit.jupiter.api.Test;
import org.redisson.api.RKeys;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedisEpisodicMemoryStoreDeleteCellTest {

    private final RedissonClient redisson = mock(RedissonClient.class);
    private final RScript script = mock(RScript.class);
    private final RKeys keys = mock(RKeys.class);
    private final RedisEpisodicMemoryStore store = new RedisEpisodicMemoryStore(redisson, new ObjectMapper(), 7200);

    private final MemoryId id = MemoryId.of("episodic-u1", MemoryType.EPISODIC, "u1");
    private final MemoryCellId cellId = MemoryCellId.of("cell-1");

    @Test
    void deleteCellRunsLuaScriptOnTenantScopedKey() {
        when(redisson.getScript(StringCodec.INSTANCE)).thenReturn(script);
        when(redisson.getKeys()).thenReturn(keys);
        when(script.eval(eq(RScript.Mode.READ_WRITE), any(String.class),
                eq(RScript.ReturnType.INTEGER), anyList(), any(Object[].class)))
                .thenReturn(1L);

        boolean removed = store.deleteCell(id, cellId);

        assertThat(removed).isTrue();
        // WP-5: key 必须含 ownerKey 前缀，防止跨用户碰撞
        verify(redisson).getScript(StringCodec.INSTANCE);
        verify(script).eval(eq(RScript.Mode.READ_WRITE), any(String.class),
                eq(RScript.ReturnType.INTEGER), eq(List.of("ai:memory:episodic:u1:episodic-u1")),
                any(Object[].class));
    }

    @Test
    void deleteCellReturnsFalseWhenScriptReportsNoMatch() {
        when(redisson.getScript(StringCodec.INSTANCE)).thenReturn(script);
        when(script.eval(eq(RScript.Mode.READ_WRITE), any(String.class),
                eq(RScript.ReturnType.INTEGER), anyList(), any(Object[].class)))
                .thenReturn(0L);

        boolean removed = store.deleteCell(id, cellId);

        assertThat(removed).isFalse();
    }

    @Test
    void deleteCellFallsBackToDefaultRewriteOnScriptFailure() {
        when(redisson.getScript(StringCodec.INSTANCE)).thenReturn(script);
        when(script.eval(eq(RScript.Mode.READ_WRITE), any(String.class),
                eq(RScript.ReturnType.INTEGER), anyList(), any(Object[].class)))
                .thenThrow(new IllegalStateException("script eval failed"));
        @SuppressWarnings({"unchecked", "rawtypes"})
        org.redisson.api.RList list = mock(org.redisson.api.RList.class);
        when(list.readAll()).thenReturn(List.of());
        when((org.redisson.api.RList) redisson.getList(any(String.class), any())).thenReturn(list);

        boolean removed = store.deleteCell(id, cellId);

        // 回退路径：readAll 为空 → 无匹配 → false（不抛异常）
        assertThat(removed).isFalse();
    }
}
