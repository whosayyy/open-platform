package com.yin.ai.platform.config.feature;

import java.util.Map;
import java.util.Set;

/**
 * Feature Flag 存储 SPI — 支持热更新无需重启。
 */
public interface FeatureFlagStore {

    /**
     * 检查指定 flag 是否启用。未配置的 flag 默认返回 true（不阻塞）。
     */
    boolean isEnabled(String key);

    /**
     * 设置 flag 状态。
     */
    void setEnabled(String key, boolean enabled);

    /**
     * 获取所有 flag 及其状态。
     */
    Map<String, Boolean> listAll();

    /**
     * 获取已被禁用的 flag 集合（用于快速检查）。
     */
    Set<String> getDisabledFlags();
}
