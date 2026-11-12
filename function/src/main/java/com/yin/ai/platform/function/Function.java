package com.yin.ai.platform.function;

import java.util.concurrent.CompletableFuture;

/**
 * Function SPI 接口 — 所有外部能力的根接口。
 * <p>
 * 每个 Function 代表一个外部系统操作的封装：
 * <ul>
 *   <li>SearchFunction → Tavily 搜索 API</li>
 *   <li>PublishFunction → 发布到 content-service (Feign)</li>
 *   <li>WebPageFunction → 读取网页全文 (Jsoup)</li>
 * </ul>
 * <p>
 * 约束：
 * <ul>
 *   <li>不包含业务逻辑（无 if/else 分支）</li>
 *   <li>不调用其他 Function</li>
 *   <li>不调用 AI Gateway</li>
 *   <li>只操作一个外部系统</li>
 * </ul>
 */
@Deprecated(since = "0.0.1", forRemoval = false)
public interface Function {

    /** Function 元数据。 */
    FunctionDefinition getDefinition();

    /**
     * 同步执行（阻塞式）。
     *
     * @param input 标准化输入
     * @return 执行结果
     */
    FunctionResult execute(FunctionInput input);

    /**
     * 异步执行（可选，适用于耗时操作）。
     */
    default CompletableFuture<FunctionResult> executeAsync(FunctionInput input) {
        return CompletableFuture.completedFuture(execute(input));
    }

    /**
     * 是否支持幂等（默认 false）。
     * 返回 true 的 Function，执行前检查 requestId 是否已处理。
     */
    default boolean isIdempotent() {
        return false;
    }
}
