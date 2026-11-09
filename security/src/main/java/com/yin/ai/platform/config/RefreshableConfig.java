package com.yin.ai.platform.config;

import org.redisson.api.RedissonClient;
import org.redisson.api.listener.MessageListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 可热更新的配置基类 — 监听 Redis Pub/Sub 频道在运行时刷新配置。
 * <p>
 * 子类在构造时调用 {@link #subscribe(String)} 注册监听，
 * 收到消息时调用 {@link #onRefresh(String, String)} 处理。
 * <p>
 * 仅对关键运行时参数（temperature、maxTokens、model 切换等）支持热更新，
 * 线程池大小、队列容量等基础设施参数仍需重启生效。
 */
public abstract class RefreshableConfig {

    private static final Logger log = LoggerFactory.getLogger(RefreshableConfig.class);
    private static final String CHANNEL_PREFIX = "ai:config:refresh:";

    private final RedissonClient redisson;

    protected RefreshableConfig(RedissonClient redisson) {
        this.redisson = redisson;
    }

    /**
     * 订阅指定配置键的更新通知。
     */
    protected void subscribe(String configKey) {
        String channel = CHANNEL_PREFIX + configKey;
        redisson.getTopic(channel).addListener(String.class, (charSequence, value) -> {
            log.info("[REFRESH-CFG] channel={} newValue={}", channel, value);
            try {
                onRefresh(configKey, value.toString());
            } catch (Exception e) {
                log.warn("[REFRESH-CFG] refresh failed channel={}: {}", channel, e.getMessage());
            }
        });
        log.info("[REFRESH-CFG] subscribed to channel={}", channel);
    }

    /**
     * 发布配置变更通知（供管理端调用）。
     */
    public void publish(String configKey, String newValue) {
        redisson.getTopic(CHANNEL_PREFIX + configKey).publish(newValue);
        log.info("[REFRESH-CFG] published {}=\"{}\"", configKey, newValue);
    }

    /**
     * 子类实现此方法处理具体的配置刷新逻辑。
     *
     * @param key   配置键
     * @param value 新值（字符串形式，子类自行解析类型）
     */
    protected abstract void onRefresh(String key, String value);
}
