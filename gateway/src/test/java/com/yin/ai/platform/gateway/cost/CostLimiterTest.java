package com.yin.ai.platform.gateway.cost;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

@DisplayName("CostLimiter — 成本限制器")
class CostLimiterTest {

    private CostLimiter limiter;

    @BeforeEach
    void setUp() {
        limiter = new CostLimiter(0.50, 5.0, 20.0);
    }

    @Test
    @DisplayName("请求预算未超时正常通过")
    void shouldPassWithinRequestBudget() {
        assertThatCode(() -> limiter.checkBeforeRequest("user1", 0.01))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("请求预算超限时抛出异常")
    void shouldThrowOnExceededRequestBudget() {
        assertThatThrownBy(() -> limiter.checkBeforeRequest("user1", 1.0))
                .isInstanceOf(CostLimitExceededException.class)
                .hasMessageContaining("per-request");
    }

    @Test
    @DisplayName("预估成本为 0 时跳过检查")
    void shouldSkipZeroCost() {
        assertThatCode(() -> limiter.checkBeforeRequest("user1", 0))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("null 用户只检查请求级预算")
    void shouldOnlyCheckRequestBudgetForNullUser() {
        assertThatCode(() -> limiter.checkBeforeRequest(null, 0.01))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("日预算累计超限时抛出异常")
    void shouldThrowOnExceededDailyBudget() {
        limiter.recordCost("user2", 4.95);
        assertThatThrownBy(() -> limiter.checkBeforeRequest("user2", 0.1))
                .isInstanceOf(CostLimitExceededException.class)
                .hasMessageContaining("daily");
    }

    @Test
    @DisplayName("月预算累计超限时抛出异常")
    void shouldThrowOnExceededMonthlyBudget() {
        CostLimiter monthlyLimiter = new CostLimiter(0.50, 50.0, 20.0);
        monthlyLimiter.recordCost("user3", 19.9);
        assertThatThrownBy(() -> monthlyLimiter.checkBeforeRequest("user3", 0.2))
                .isInstanceOf(CostLimitExceededException.class)
                .hasMessageContaining("monthly");
    }

    @Test
    @DisplayName("recordCost 后 getDailyCost 返回正确值")
    void shouldTrackDailyCost() {
        limiter.recordCost("user4", 0.0001);
        limiter.recordCost("user4", 0.0002);
        assertThat(limiter.getDailyCost("user4")).isCloseTo(0.0003, within(0.00001));
    }

    @Test
    @DisplayName("getDailyCost 对未知用户返回 0")
    void shouldReturnZeroForUnknownUser() {
        assertThat(limiter.getDailyCost("unknown")).isEqualTo(0);
    }

    @Test
    @DisplayName("reset 清除用户成本")
    void shouldResetUserCost() {
        limiter.recordCost("user5", 1.0);
        limiter.reset("user5");
        assertThat(limiter.getDailyCost("user5")).isEqualTo(0);
    }

    @Test
    @DisplayName("不限预算时永不抛出")
    void shouldNeverThrowWithUnlimitedBudget() {
        CostLimiter unlimited = new CostLimiter(0, 0, 0);
        assertThatCode(() -> unlimited.checkBeforeRequest("user6", 100.0))
                .doesNotThrowAnyException();
    }
}
