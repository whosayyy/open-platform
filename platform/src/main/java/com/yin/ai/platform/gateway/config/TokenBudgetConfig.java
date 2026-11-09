package com.yin.ai.platform.gateway.config;

import lombok.Data;

@Data
public class TokenBudgetConfig {
    /** 是否启用 Token 预算限制 */
    private boolean enabled = true;
    /** 对话历史最大 Token 数（超出后滑动窗口截断） */
    private int maxHistoryTokens = 4096;
}
