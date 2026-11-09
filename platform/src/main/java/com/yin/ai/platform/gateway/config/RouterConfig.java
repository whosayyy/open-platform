package com.yin.ai.platform.gateway.config;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class RouterConfig {
    /** 路由策略：lowest-cost | priority-fallback */
    private String strategy = "lowest-cost";
    /** priority-fallback 策略的模型优先级列表 */
    private List<String> priorityOrder = new ArrayList<>();
}
