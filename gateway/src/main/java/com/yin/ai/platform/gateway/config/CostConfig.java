package com.yin.ai.platform.gateway.config;

import lombok.Data;

@Data
public class CostConfig {
    /** 单次请求最大成本（USD，0 = 不限） */
    private double perRequestBudgetUsd;
    /** 每用户每日最大成本（USD，0 = 不限） */
    private double perUserDailyBudgetUsd;
    /** 每用户每月最大成本（USD，0 = 不限） */
    private double perUserMonthlyBudgetUsd;
}
