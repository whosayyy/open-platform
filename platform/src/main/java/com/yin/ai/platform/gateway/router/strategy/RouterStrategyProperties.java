package com.yin.ai.platform.gateway.router.strategy;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 模型路由策略配置。
 */
@Data
public class RouterStrategyProperties {

    /** 路由策略名称：lowest-cost | priority-fallback */
    private String strategy = "lowest-cost";

    /** PriorityFallbackStrategy 的模型优先级列表 */
    private List<String> priorityOrder = new ArrayList<>();
}
