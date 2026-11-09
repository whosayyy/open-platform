package com.yin.ai.platform.agent;

import com.yin.ai.platform.agent.executor.AgentExecutor;
import com.yin.ai.platform.agent.executor.StepResult;
import com.yin.ai.platform.agent.observer.Observation;
import com.yin.ai.platform.agent.observer.Observer;
import com.yin.ai.platform.agent.planner.Plan;
import com.yin.ai.platform.agent.planner.Planner;
import com.yin.ai.platform.agent.planner.Step;
import com.yin.ai.platform.agent.reflector.Decision;
import com.yin.ai.platform.agent.reflector.Reflector;
import com.yin.ai.platform.engine.ExecutionContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ReAct 循环的重试上限契约测试。
 * <p>
 * 历史上这里有一个隐蔽的死循环：循环开头每轮无条件 {@code resetRetryCount()}，
 * 而 RETRY 决策不推进 stepIndex（重入同一步骤），于是计数被反复清零、
 * {@code hasExceededRetries()} 恒为 false，同一步骤可以无限重试，
 * 只能靠 maxSteps / timeout 兜底，{@code maxRetriesPerStep} 完全不生效。
 */
@DisplayName("ReActLoop 重试上限")
class ReActLoopRetryContractTest {

    private static final Duration MAX_WAIT = Duration.ofSeconds(5);

    private ExecutionContext execCtx() {
        return new ExecutionContext(
                UUID.randomUUID().toString(), null, null, null, null,
                Map.of(), null, null, Instant.now(), Map.of());
    }

    private AgentContext context(int maxRetriesPerStep) {
        AgentConfig config = new AgentConfig(
                12, Duration.ofMinutes(1), maxRetriesPerStep, false, 1,
                com.yin.ai.platform.agent.planner.PlanningMode.TEMPLATE, false);
        AgentTask task = new AgentTask("retry limit goal", Map.of(), null, config);
        return new AgentContext(execCtx(), task, 0);
    }

    private ReActLoop loop(AtomicInteger stepExecutions, AtomicInteger reflections, long stepSleepMs) {
        Planner planner = ctx -> Plan.ofSteps(List.of(Step.of("s1", "only one step")));

        AgentExecutor executor = (step, ctx) -> {
            stepExecutions.incrementAndGet();
            if (stepSleepMs > 0) {
                try {
                    Thread.sleep(stepSleepMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return StepResult.ok("step output", stepSleepMs);
        };

        Observer observer = (result, ctx) -> Observation.success("observed");
        // 永远要求重试：唯一的终止途径只能是 maxRetriesPerStep 生效
        Reflector reflector = (observation, ctx) -> {
            reflections.incrementAndGet();
            return Decision.RETRY;
        };

        return new ReActLoop(planner, executor, observer, reflector);
    }

    @Test
    @DisplayName("maxRetriesPerStep 生效：同一步骤不会无限重试")
    void retryLimitStopsInfiniteRetry() {
        int maxRetries = 2;
        AtomicInteger stepExecutions = new AtomicInteger();
        AtomicInteger reflections = new AtomicInteger();
        ReActLoop loop = loop(stepExecutions, reflections, 20);

        AgentContext ctx = context(maxRetries);

        long start = System.currentTimeMillis();
        AgentResult result = loop.enter(ctx);
        long elapsed = System.currentTimeMillis() - start;

        assertThat(elapsed).as("must finish within the retry budget, not loop forever")
                .isLessThan(MAX_WAIT.toMillis());
        assertThat(result).isNotNull();

        // 每步的执行次数上限 = 1 次初始执行 + maxRetries 次重试
        assertThat(stepExecutions.get()).isLessThanOrEqualTo(maxRetries + 1);
        // 反射调用次数应与执行次数一致（每次执行后都反射一次）
        assertThat(reflections.get()).isLessThanOrEqualTo(maxRetries + 1);
    }

    @Test
    @DisplayName("重试计数在换步骤时归零，不会跨步骤累积")
    void retryCountResetsOnNewStep() {
        AtomicInteger stepExecutions = new AtomicInteger();
        AtomicInteger reflections = new AtomicInteger();
        List<String> execOrder = new ArrayList<>();
        List<Integer> retryCountsOnEntry = new ArrayList<>();

        Planner planner = ctx -> Plan.ofSteps(List.of(
                Step.of("s1", "step one"), Step.of("s2", "step two")));

        AgentExecutor executor = (step, ctx) -> {
            stepExecutions.incrementAndGet();
            execOrder.add(step.id());
            retryCountsOnEntry.add(ctx.getRetryCount());
            return StepResult.ok("out-" + step.id(), 1);
        };
        Observer observer = (result, ctx) -> Observation.success("observed");
        // 第一次反射要求重试，第二次放行 —— 保证每个步骤都会重试一次再继续。
        // 若重试计数失效（恒为 0），这里会一直返回 RETRY，只能靠 maxSteps 兜底，
        // 步骤会一直停在 s1，下面的 execOrder 断言就会失败。
        Reflector reflector = (observation, ctx) -> {
            reflections.incrementAndGet();
            return ctx.getRetryCount() == 0 ? Decision.RETRY : Decision.CONTINUE;
        };

        ReActLoop loop = new ReActLoop(planner, executor, observer, reflector);
        AgentContext ctx = context(3);

        long start = System.currentTimeMillis();
        AgentResult result = loop.enter(ctx);
        long elapsed = System.currentTimeMillis() - start;

        assertThat(result).isNotNull();
        assertThat(elapsed).as("retry count must actually increment")
                .isLessThan(MAX_WAIT.toMillis());
        assertThat(reflections.get()).isGreaterThanOrEqualTo(2);

        // 真的推进到了第二个步骤 —— 这只有在重试计数生效时才可能发生
        assertThat(execOrder).contains("s1", "s2");
        assertThat(execOrder).containsSubsequence("s1", "s1", "s2");

        // 观察到重试时计数为 1（说明 incrementRetryCount 真的被执行了）
        assertThat(retryCountsOnEntry).contains(1);

        // 核心不变量：每次切换到新步骤时，重试计数必须已经归零
        for (int i = 1; i < execOrder.size(); i++) {
            if (!execOrder.get(i).equals(execOrder.get(i - 1))) {
                assertThat(retryCountsOnEntry.get(i))
                        .as("首次进入新步骤 %s 时重试计数应为 0", execOrder.get(i))
                        .isZero();
            }
        }
    }

    @Test
    @DisplayName("流式执行结束后 fluxSink 变量被清理且不抛异常")
    void streamClearsFluxSinkVariable() {
        AtomicInteger stepExecutions = new AtomicInteger();
        AtomicInteger reflections = new AtomicInteger();
        ReActLoop loop = loop(stepExecutions, reflections, 0);
        AgentContext ctx = context(3);

        List<AgentEvent> events = loop.enterStream(ctx).collectList().block(MAX_WAIT);

        assertThat(events).isNotNull().isNotEmpty();
        // 原实现向 ConcurrentHashMap 写入 null，会在 finally 抛 NPE 并留下残留变量
        assertThat(ctx.getVariable("agent.fluxSink")).isNull();
    }
}
