package com.yin.ai.platform.memory.lifecycle;

import com.yin.ai.platform.core.memory.LifecycleState;
import com.yin.ai.platform.core.memory.MemoryId;
import com.yin.ai.platform.core.memory.MemoryType;

/**
 * 生命周期钩子 SPI — 在 Memory 状态转换前后插入自定义逻辑。
 * <p>
 * 典型用途：校验（onPreTransition 抛异常阻止转换）、审计日志、指标采集、自动 Checkpoint。
 * 遵循已有 SPI 模式：每个钩子声明自己关心的 MemoryType 和状态转换，由 MemoryLifecycle 按序调用。
 */
public interface LifecycleHook {

    /** 是否关心此转换。返回 false 则跳过，避免不必要的回调开销。 */
    boolean supports(MemoryType type, LifecycleState from, LifecycleState to);

    /** 转换前回调。抛异常可阻止本次转换（如校验失败）。 */
    default void onPreTransition(MemoryId id, LifecycleState from, LifecycleState to) {
    }

    /** 转换成功后回调。异常被捕获为 ERROR 事件，不阻止后续钩子执行。 */
    default void onPostTransition(MemoryId id, LifecycleState from, LifecycleState to) {
    }

    /** 转换失败回调（CAS 竞争失败或 onPreTransition 抛异常）。 */
    default void onTransitionFailed(MemoryId id, LifecycleState from, LifecycleState to, Throwable error) {
    }
}
