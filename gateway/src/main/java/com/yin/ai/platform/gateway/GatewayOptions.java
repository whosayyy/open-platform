package com.yin.ai.platform.gateway;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * AI Gateway 调用选项。
 */
public class GatewayOptions {
    /** 指定模型（null = 默认模型） */
    private String model;
    /** 温度（null = 默认值） */
    private Double temperature;
    /** 最大 Token 数（null = 默认值） */
    private Integer maxTokens;
    /** 是否流式 */
    private Boolean streamEnabled;
    /** 启用的 Filter 列表（null = 全部启用） */
    private List<String> enabledFilters;
    /** 禁用的 Filter 列表 */
    private List<String> disabledFilters;
    /** 调用超时 */
    private Duration timeout;
    /** 首 Token 超时（流式场景） */
    private Duration firstTokenTimeout;
    /** 是否启用 Function Calling */
    private boolean enableFunctionCalling;
    /** 是否让 Spring AI 自动执行 Tool（false = 手动编排） */
    private boolean enableToolExecution;
    /** 是否强制 LLM 输出 JSON（设置 response_format = json_object） */
    private boolean jsonResponse;
    /** 强制工具名称（非 null = 注册该 capture-only 工具，参数即结构化输出） */
    private String forcedToolName;
    /** 强制工具的 JSON Schema（与 forcedToolName 搭配） */
    private Map<String, Object> outputSchema;
    /** 是否强制 tool_choice 单轮直接调用 forcedToolName（false = 只注册工具，模型可自行决策） */
    private boolean forceToolChoice;
    /** Provider-neutral reasoning decision for this execution. */
    private ModelExecutionOptions modelExecutionOptions;

    public GatewayOptions() {}

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }

    public Double getTemperature() { return temperature; }
    public void setTemperature(Double temperature) { this.temperature = temperature; }

    public Integer getMaxTokens() { return maxTokens; }
    public void setMaxTokens(Integer maxTokens) { this.maxTokens = maxTokens; }

    public Boolean getStreamEnabled() { return streamEnabled; }
    public void setStreamEnabled(Boolean streamEnabled) { this.streamEnabled = streamEnabled; }

    public List<String> getEnabledFilters() { return enabledFilters; }
    public void setEnabledFilters(List<String> enabledFilters) { this.enabledFilters = enabledFilters; }

    public List<String> getDisabledFilters() { return disabledFilters; }
    public void setDisabledFilters(List<String> disabledFilters) { this.disabledFilters = disabledFilters; }

    public Duration getTimeout() { return timeout; }
    public void setTimeout(Duration timeout) { this.timeout = timeout; }

    public Duration getFirstTokenTimeout() { return firstTokenTimeout; }
    public void setFirstTokenTimeout(Duration firstTokenTimeout) { this.firstTokenTimeout = firstTokenTimeout; }

    public boolean isEnableFunctionCalling() { return enableFunctionCalling; }
    public void setEnableFunctionCalling(boolean enableFunctionCalling) { this.enableFunctionCalling = enableFunctionCalling; }

    public boolean isEnableToolExecution() { return enableToolExecution; }
    public void setEnableToolExecution(boolean enableToolExecution) { this.enableToolExecution = enableToolExecution; }

    public boolean isJsonResponse() { return jsonResponse; }
    public void setJsonResponse(boolean jsonResponse) { this.jsonResponse = jsonResponse; }

    public String getForcedToolName() { return forcedToolName; }
    public void setForcedToolName(String forcedToolName) { this.forcedToolName = forcedToolName; }

    public Map<String, Object> getOutputSchema() { return outputSchema; }
    public void setOutputSchema(Map<String, Object> outputSchema) { this.outputSchema = outputSchema; }

    public boolean isForceToolChoice() { return forceToolChoice; }
    public void setForceToolChoice(boolean forceToolChoice) { this.forceToolChoice = forceToolChoice; }

    public ModelExecutionOptions getModelExecutionOptions() { return modelExecutionOptions; }
    public void setModelExecutionOptions(ModelExecutionOptions modelExecutionOptions) {
        this.modelExecutionOptions = modelExecutionOptions;
    }

    /** 返回深拷贝（列表字段独立副本），供适配器构造降级请求。 */
    public GatewayOptions copy() {
        GatewayOptions copy = new GatewayOptions();
        copy.model = this.model;
        copy.temperature = this.temperature;
        copy.maxTokens = this.maxTokens;
        copy.streamEnabled = this.streamEnabled;
        copy.enabledFilters = this.enabledFilters != null ? new ArrayList<>(this.enabledFilters) : null;
        copy.disabledFilters = this.disabledFilters != null ? new ArrayList<>(this.disabledFilters) : null;
        copy.timeout = this.timeout;
        copy.firstTokenTimeout = this.firstTokenTimeout;
        copy.enableFunctionCalling = this.enableFunctionCalling;
        copy.enableToolExecution = this.enableToolExecution;
        copy.jsonResponse = this.jsonResponse;
        copy.forcedToolName = this.forcedToolName;
        copy.outputSchema = this.outputSchema;
        copy.forceToolChoice = this.forceToolChoice;
        copy.modelExecutionOptions = this.modelExecutionOptions;
        return copy;
    }
}
