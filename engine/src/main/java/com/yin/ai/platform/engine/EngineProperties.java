package com.yin.ai.platform.engine;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@Data
@ConfigurationProperties(prefix = "ai.engine")
public class EngineProperties {

    private Timeout timeout = new Timeout();
    private Retry retry = new Retry();
    private Cancellation cancellation = new Cancellation();
    private ContextPropagation contextPropagation = new ContextPropagation();
    private Metrics metrics = new Metrics();
    private ThreadPool threadPool = new ThreadPool();

    @Data
    public static class Timeout {
        private Duration defaultTimeout = Duration.ofSeconds(30);
        private Duration llmCall = Duration.ofSeconds(120);
        private Duration functionExecution = Duration.ofSeconds(10);
        private Duration ragRetrieve = Duration.ofSeconds(5);
        private Duration parallelTotal = Duration.ofSeconds(60);
    }

    @Data
    public static class Retry {
        private int maxRetries = 2;
        private Duration initialBackoff = Duration.ofSeconds(1);
        private double backoffMultiplier = 2.0;
        private Duration maxBackoff = Duration.ofSeconds(4);
    }

    @Data
    public static class Cancellation {
        private boolean enabled = true;
        /**
         * 客户端断连时是否取消任务。
         * 默认 false：前端 keep-alive 后切页面不触发断连；
         * 即使真正关闭页面，任务也应继续运行（结果持久化到 Redis，前端可恢复）。
         */
        private boolean onClientDisconnect = false;
        private boolean onTimeout = true;
        /**
         * 客户端断连后，延迟取消的宽限期（秒）。
         * 仅当 onClientDisconnect=true 时生效。给前端切页面/重连留出时间窗口。
         */
        private int disconnectGraceSeconds = 30;
    }

    @Data
    public static class ContextPropagation {
        private boolean springSecurity = true;
        private boolean mdc = true;
        private boolean opentelemetry = true;
        private boolean customAttributes = true;
    }

    @Data
    public static class Metrics {
        private boolean enabled = true;
        private boolean exportTaskExecution = true;
        private boolean exportThreadPool = true;
    }

    @Data
    public static class ThreadPool {
        private int coreSize = 10;
        private int maxSize = 20;
        private int queueCapacity = 100;
        private int keepAliveSeconds = 60;
        private int awaitTerminationSeconds = 30;
        private String threadNamePrefix = "ai-engine-";
    }

    /** 集群全局最大并发数，0 表示仅使用本地 Semaphore 不做全局限制 */
    private int maxGlobalConcurrency = 0;
}
