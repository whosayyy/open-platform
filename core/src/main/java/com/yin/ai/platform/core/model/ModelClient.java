package com.yin.ai.platform.core.model;

import com.yin.ai.platform.core.context.ExecutionContext;

/**
 * Model provider SPI. Spring AI and direct SDK integrations implement this interface.
 *
 * <h2>实现方必须遵守的契约</h2>
 *
 * <p><b>1. 必须回传 assistant 消息上的 {@code toolCalls}。</b>
 * 多轮工具调用时，编排层会把上一轮模型返回的工具调用放进 assistant 消息的
 * {@link ModelMessage#metadata()} 的 {@code toolCalls} 键（值为
 * {@code List<ModelToolCall>}），并在其后追加 {@code role=tool} 的结果消息。
 *
 * <p>OpenAI 兼容协议要求 {@code role=tool} 的消息必须前置一条声明了对应
 * {@code tool_call_id} 的 assistant 消息，否则服务端会返回 400：
 * <pre>
 * Messages with role 'tool' must be a response to a preceding message with 'tool_calls'
 * </pre>
 * 因此实现方在把消息序列化成请求体时，<b>必须从 metadata 读取 {@code toolCalls}
 * 并写回请求</b>。忽略它会在真实模型上直接失败，而单元测试里的假模型通常不会暴露
 * 这个问题。
 *
 * <p><b>2. 工具参数 schema 由 {@link ModelRequest#toolDefinitions()} 提供。</b>
 * 平台编排在调用模型时会填充完整的 {@code ToolDefinition}（含 {@code inputSchema}），
 * 实现方直接使用即可，<b>不需要自己持有 {@code ToolRegistry}</b>。
 *
 * <p>{@link ModelRequest#allowedTools()}（工具名列表）作为兼容字段保留，当
 * {@code toolDefinitions} 为空时才需要回退到它——那种情况下 schema 未知，
 * 只能给模型一个空参数对象，工具调用质量会下降。
 *
 * <p><b>3. 超时由调用方保证。</b>
 * 引擎在调用方一侧强制 {@code ExecutionOptions.timeout()} 并会中断线程；
 * 实现方仍应设置自己的连接与读取超时，避免连接泄漏。
 */
public interface ModelClient {

    /** 同步调用。实现方必须把失败表达为异常，由编排层转换为失败结果。 */
    ModelResponse execute(ModelRequest request, ExecutionContext context);

    /**
     * 异步/流式调用。
     * <p>
     * 增量文本通过 {@link ModelEventListener} 推送；未实现流式的适配器可以退化为
     * 一次 {@link #execute} 后推送完整文本，或明确抛出
     * {@link UnsupportedOperationException}，但不要静默返回空结果。
     */
    ModelCall submit(ModelRequest request, ExecutionContext context,
                     ModelEventListener listener);
}
