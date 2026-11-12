package com.yin.ai.platform.adapter.runtime.persistence;

import com.yin.ai.platform.agent.task.TaskTokenBudget;
import com.yin.ai.platform.agent.task.TaskTokenSummary;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RedisTaskTokenAccountantTest {

    @Test
    void enforcesTaskAndStepBudgetsWithLocalFallback() {
        RedisTaskTokenAccountant accountant = new RedisTaskTokenAccountant(null);
        accountant.setBudget("task-1", new TaskTokenBudget(100, 70, 2, 80));

        accountant.record("task-1", "research", "model-a", 40);
        accountant.record("task-1", "research", "model-a", 35);

        TaskTokenSummary summary = accountant.getSummary("task-1");
        assertThat(summary.totalTokens()).isEqualTo(75);
        assertThat(summary.tokensByStep()).containsEntry("research", 75L);
        assertThat(summary.tokensByModel()).containsEntry("model-a", 75L);
        assertThat(accountant.canExecuteStep("task-1", "research")).isFalse();
        assertThat(summary.budgetExceeded()).isFalse();

        accountant.record("task-1", "write", "model-b", 30);
        assertThat(accountant.isOverBudget("task-1")).isTrue();
    }

    @Test
    void cleanupRemovesLocalState() {
        RedisTaskTokenAccountant accountant = new RedisTaskTokenAccountant(null);
        accountant.record("task-2", "step", "model", 10);
        accountant.cleanup("task-2");
        assertThat(accountant.getSummary("task-2").totalTokens()).isZero();
    }
}
