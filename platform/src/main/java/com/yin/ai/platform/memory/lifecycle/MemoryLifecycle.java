package com.yin.ai.platform.memory.lifecycle;

import com.yin.ai.platform.core.memory.LifecycleState;
import com.yin.ai.platform.core.memory.MemoryId;
import com.yin.ai.platform.core.memory.MemoryType;
import com.yin.ai.platform.memory.event.MemoryEventBus;
import com.yin.ai.platform.memory.event.MemoryEventType;
import com.yin.ai.platform.memory.exception.MemoryConcurrencyException;
import com.yin.ai.platform.memory.exception.MemoryNotFoundException;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Memory 生命周期管理器 — 基于 CAS 状态机控制 Memory 从 CREATED 到 DELETED 的完整生命周期。
 * <p>
 * 设计理由：
 * <ul>
 *   <li>复用 {@link Task#transitionTo} 的 CAS 模式：AtomicReference + compareAndSet，零锁</li>
 *   <li>通过 {@link LifecycleHook} 链在转换前后插入审计/校验/自动 Checkpoint 等逻辑</li>
 *   <li>所有转换发布 {@link MemoryEventBus} 事件，使 Observability 和自动 Consolidation 监听成为可能</li>
 *   <li>不支持回滚 — 状态机严格单向，失败走 ERROR 事件 + RecoveryCoordinator 恢复</li>
 * </ul>
 */
public class MemoryLifecycle {

    private final Map<MemoryId, AtomicReference<LifecycleState>> states = new ConcurrentHashMap<>();
    private final List<LifecycleHook> hooks = new CopyOnWriteArrayList<>();
    private final MemoryEventBus eventBus;

    public MemoryLifecycle(MemoryEventBus eventBus) {
        this.eventBus = eventBus;
    }

    public void registerHook(LifecycleHook hook) {
        hooks.add(hook);
    }

    /** 注册一个 Memory 并返回其 ID */
    public MemoryId register(MemoryId id) {
        states.put(id, new AtomicReference<>(LifecycleState.CREATED));
        eventBus.publish(MemoryEventType.CREATED, id, null, null, Map.of());
        return id;
    }

    /** 获取当前状态，不存在时抛异常 */
    public LifecycleState getState(MemoryId id) {
        AtomicReference<LifecycleState> ref = states.get(id);
        if (ref == null) {
            throw new MemoryNotFoundException("Memory not registered: " + id);
        }
        return ref.get();
    }

    /** 执行状态转换，返回是否成功 */
    public boolean transition(MemoryId id, LifecycleState target) {
        AtomicReference<LifecycleState> ref = states.get(id);
        if (ref == null) {
            throw new MemoryNotFoundException("Memory not registered: " + id);
        }

        LifecycleState current = ref.get();
        if (!current.isTransitionAllowed(target)) {
            return false;
        }

        runPreHooks(id, current, target);

        if (!ref.compareAndSet(current, target)) {
            LifecycleState actual = ref.get();
            Throwable error = new MemoryConcurrencyException(
                    "CAS failed: expected " + current + " → " + target + ", actual " + actual);
            runFailedHooks(id, current, target, error);
            eventBus.publish(MemoryEventType.ERROR, id, null, null,
                    Map.of("from", current, "to", target, "actual", actual, "error", error.getMessage()));
            return false;
        }

        runPostHooks(id, current, target);
        eventBus.publish(mapToEventType(target), id, null, null,
                Map.of("from", current, "to", target));
        return true;
    }

    /** 强制状态转换 — 仅在恢复/清理场景使用，跳过钩子 */
    public void forceTransition(MemoryId id, LifecycleState target) {
        AtomicReference<LifecycleState> ref = states.get(id);
        if (ref == null) {
            throw new MemoryNotFoundException("Memory not registered: " + id);
        }
        LifecycleState previous = ref.getAndSet(target);
        eventBus.publish(mapToEventType(target), id, null, null,
                Map.of("from", previous, "to", target, "forced", true));
    }

    /** 移除 Memory 的注册信息 */
    public void unregister(MemoryId id) {
        states.remove(id);
    }

    private void runPreHooks(MemoryId id, LifecycleState from, LifecycleState to) {
        for (LifecycleHook hook : hooks) {
            if (hook.supports(id.type(), from, to)) {
                hook.onPreTransition(id, from, to);
            }
        }
    }

    private void runPostHooks(MemoryId id, LifecycleState from, LifecycleState to) {
        for (LifecycleHook hook : hooks) {
            if (hook.supports(id.type(), from, to)) {
                try {
                    hook.onPostTransition(id, from, to);
                } catch (Exception e) {
                    eventBus.publish(MemoryEventType.ERROR, id, null, null,
                            Map.of("hook", hook.getClass().getSimpleName(), "error", e.getMessage()));
                }
            }
        }
    }

    private void runFailedHooks(MemoryId id, LifecycleState from, LifecycleState to, Throwable error) {
        for (LifecycleHook hook : hooks) {
            if (hook.supports(id.type(), from, to)) {
                try {
                    hook.onTransitionFailed(id, from, to, error);
                } catch (Exception ignored) {
                }
            }
        }
    }

    private MemoryEventType mapToEventType(LifecycleState state) {
        return switch (state) {
            case LOADING -> MemoryEventType.LOADED;
            case ACTIVE -> MemoryEventType.HYDRATED;
            case CONSOLIDATING -> MemoryEventType.CONSOLIDATED;
            case COMPRESSING -> MemoryEventType.COMPRESSED;
            case ARCHIVED -> MemoryEventType.ARCHIVED;
            case DELETED -> MemoryEventType.DELETED;
            case RECOVERING -> MemoryEventType.RECOVERING;
            default -> MemoryEventType.CREATED;
        };
    }
}
