package com.yin.ai.platform.agent.pattern;

import com.yin.ai.platform.agent.event.AgentEvent;
import com.yin.ai.platform.agent.event.EventPublisher;
import com.yin.ai.platform.agent.pattern.context.ContextManager;
import com.yin.ai.platform.agent.pattern.executor.StepExecutorDispatcher;
import com.yin.ai.platform.agent.pattern.reflect.StepReflector;
import com.yin.ai.platform.agent.pattern.research.ClarificationPhase;
import com.yin.ai.platform.core.runtime.research.ClarificationResult;
import com.yin.ai.platform.core.runtime.research.IterativeExecutionResult;
import com.yin.ai.platform.agent.pattern.research.IterativeExecutor;
import com.yin.ai.platform.agent.pattern.research.ResearchPlanner;
import com.yin.ai.platform.agent.pattern.research.ResearchSynthesisPolicy;
import com.yin.ai.platform.core.rag.grounding.ClaimCitationAnalyzer;
import com.yin.ai.platform.rag.grounding.ClaimEvidenceQualityJudge;
import com.yin.ai.platform.agent.task.TaskState;
import com.yin.ai.platform.gateway.GatewayTextClient;
import com.yin.ai.platform.structured.StructuredClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

/**
 * DEEP_RESEARCH 模式 — 四阶段深度研究。
 * <p>
 * 执行流程：
 * <pre>
 * ① CLARIFICATION（需求澄清）
 *    分析用户意图 → 生成澄清问题 → WAITING → 用户回答 → clarifiedGoal
 *    （如果输入已足够明确则跳过）
 *
 * ② PLANNING（研究规划）
 *    基于 clarifiedGoal + 可用执行器约束 → 动态生成研究计划
 *    计划含：搜索策略、分析维度、验证标准、终止条件
 *
 * ③ ITERATIVE EXECUTION（迭代执行）
 *    逐步执行 + 每步 Reflector 评估：
 *    - CONTINUE → 下一步
 *    - RETRY → 重试当前步骤
 *    - REPLAN → 动态追加研究轮次
 *    - SKIP_TO_SYNTHESIS → 信息充分，提前综合
 *
 * ④ SYNTHESIS（综合输出）
 *    多源信息整合 → 结构化研究报告 → Artifact
 * </pre>
 * <p>
 * 与 PLAN_EXECUTE 的关系：
 * - DeepResearch 的执行体（阶段③）复用 IterativeExecutor，本质是增强版 PlanExecute
 * - 区别：有澄清阶段、计划可动态变更、有最大轮次限制、强调多源验证
 */
public class DeepResearchAgentPattern implements AgentPattern {

    private static final Logger log = LoggerFactory.getLogger(DeepResearchAgentPattern.class);

    private final ClarificationPhase clarificationPhase;
    private final ResearchPlanner researchPlanner;
    private final IterativeExecutor iterativeExecutor;
    private final ResearchSynthesisPolicy synthesisPolicy;
    private final GatewayTextClient chatClient;
    private ClaimEvidenceQualityJudge claimEvidenceQualityJudge;

    public DeepResearchAgentPattern(GatewayTextClient chatClient,
                                     StructuredClient structuredClient,
                                     StepExecutorDispatcher dispatcher,
                                     ContextManager contextManager,
                                     StepReflector reflector,
                                     ResearchSynthesisPolicy synthesisPolicy) {
        this.chatClient = chatClient;
        this.clarificationPhase = new ClarificationPhase(chatClient);
        this.researchPlanner = new ResearchPlanner(chatClient, structuredClient, dispatcher);
        this.iterativeExecutor = new IterativeExecutor(dispatcher, contextManager, reflector);
        this.synthesisPolicy = synthesisPolicy;
    }

    public void setClaimEvidenceQualityJudge(ClaimEvidenceQualityJudge judge) {
        this.claimEvidenceQualityJudge = judge;
    }

    @Override
    public AgentMode mode() {
        return AgentMode.DEEP_RESEARCH;
    }

    @Override
    public Flux<AgentEvent> execute(AgentExecutionContext ctx) {
        return Flux.create(sink -> {
            String taskId = ctx.getTaskId();
            EventPublisher delegate = ctx.getEventPublisher();
            EventPublisher publisher = new EventPublisher() {
                @Override
                public void publish(AgentEvent event) {
                    delegate.publish(event);
                    sink.next(event);
                }

                @Override
                public reactor.core.publisher.Flux<AgentEvent> replay(String taskId, String lastEventId) {
                    return delegate.replay(taskId, lastEventId);
                }
            };
            ctx.setEventPublisher(publisher);

            try {
                boolean resuming = Boolean.TRUE.equals(ctx.getVariable("resume"))
                        && ctx.getPlan() != null && !ctx.getPlan().steps().isEmpty()
                        && !"clarification_waiting".equals(ctx.getVariable("phase"));
                String researchGoal;
                TaskPlan plan;
                if (resuming) {
                    plan = resetInterruptedSteps(ctx.getPlan());
                    ctx.setPlan(plan);
                    researchGoal = plan.goal();
                    if (ctx.getState() == TaskState.FAILED || ctx.getState() == TaskState.PLANNING) {
                        ctx.transitionTo(TaskState.RUNNING);
                    }
                    publisher.publish(AgentEvent.progress(taskId,
                            (int) plan.completedCount(), plan.totalSteps(), "从断点继续研究..."));
                } else {
                    // === Phase 1: Clarification ===
                    var clarificationReasoning =
                            ReasoningOptionsSupport.resolve(ctx, StepType.EVIDENCE_ANALYSIS);
                    publisher.publish(AgentEvent.reasoningStarted(
                            taskId, "clarification", clarificationReasoning));
                    ClarificationResult clarification = clarificationPhase.execute(ctx);
                    publisher.publish(AgentEvent.reasoningCompleted(
                            taskId, "clarification", clarificationReasoning, 0));

                    if (clarification.isWaiting()) {
                        // 需要等待用户回答 → 暂停执行
                        log.info("[DEEP_RESEARCH] taskId={} waiting for clarification", taskId);
                        ctx.putVariable("phase", "clarification_waiting");
                        sink.complete();  // 暂停 Flux，等待 resume
                        return;
                    }

                    researchGoal = clarification.clarifiedGoal();

                    // === Phase 2: Planning ===
                    ctx.transitionTo(TaskState.PLANNING);
                    publisher.publish(AgentEvent.progress(taskId, 0, 0, "正在制定研究计划..."));

                    var planningReasoning = ReasoningOptionsSupport.resolve(ctx, StepType.PLANNING);
                    publisher.publish(AgentEvent.reasoningStarted(
                            taskId, "research-planning", planningReasoning));
                    plan = researchPlanner.plan(researchGoal, ctx);
                    publisher.publish(AgentEvent.reasoningCompleted(
                            taskId, "research-planning", planningReasoning, 0));
                    ctx.setPlan(plan);
                    ctx.transitionTo(TaskState.RUNNING);
                }

                log.info("[DEEP_RESEARCH] taskId={} plan created: {} steps",
                        taskId, plan.totalSteps());

                // === Phase 3: Iterative Execution ===
                IterativeExecutionResult execResult = iterativeExecutor.execute(ctx);

                if (!execResult.success()) {
                    publisher.publish(AgentEvent.stepFailed(taskId, "iterative-exec",
                            execResult.error()));
                    sink.error(new RuntimeException("Research execution failed: " + execResult.error()));
                    return;
                }

                log.info("[DEEP_RESEARCH] taskId={} execution done: {}/{} steps in {} rounds",
                        taskId, execResult.completedSteps(), execResult.totalSteps(),
                        execResult.roundsUsed());

                // === Phase 4: Synthesis ===
                publisher.publish(AgentEvent.progress(taskId,
                        (int) execResult.completedSteps(), execResult.totalSteps(),
                        "正在生成研究报告..."));

                var synthesisReasoning = ReasoningOptionsSupport.resolve(ctx, StepType.SYNTHESIS);
                publisher.publish(AgentEvent.reasoningStarted(
                        taskId, "research-synthesis", synthesisReasoning));
                String report = synthesize(ctx);
                publisher.publish(AgentEvent.reasoningCompleted(
                        taskId, "research-synthesis", synthesisReasoning, 0));
                ctx.putVariable("finalOutput", report);
                StepArtifactStore.ArtifactRef finalRef =
                        ctx.saveStepArtifact("result", ctx.getPlan().totalSteps(), report);
                verifyFinalArtifact(ctx, finalRef);
                ctx.addArtifact(Artifact.of("research-report.md", "markdown", report, "synthesis"));

                publisher.publish(AgentEvent.progress(taskId,
                        execResult.totalSteps(), execResult.totalSteps(), "研究完成"));

                sink.complete();

            } catch (Exception e) {
                log.error("[DEEP_RESEARCH] taskId={} failed", taskId, e);
                publisher.publish(AgentEvent.taskFailed(taskId, e.getMessage()));
                sink.error(e);
            }
        });
    }

    /**
     * 处理用户澄清回答后的恢复执行。
     * 由 UnifiedAgentRuntime.resume() 调用。
     */
    public Flux<AgentEvent> resumeAfterClarification(AgentExecutionContext ctx,
                                                      Map<String, String> answers) {
        return Flux.create(sink -> {
            String taskId = ctx.getTaskId();
            EventPublisher delegate = ctx.getEventPublisher();
            EventPublisher publisher = new EventPublisher() {
                @Override
                public void publish(AgentEvent event) {
                    delegate.publish(event);
                    sink.next(event);
                }

                @Override
                public reactor.core.publisher.Flux<AgentEvent> replay(String taskId, String lastEventId) {
                    return delegate.replay(taskId, lastEventId);
                }
            };
            ctx.setEventPublisher(publisher);

            try {
                // 合并用户回答
                var clarificationReasoning =
                        ReasoningOptionsSupport.resolve(ctx, StepType.EVIDENCE_ANALYSIS);
                publisher.publish(AgentEvent.reasoningStarted(
                        taskId, "clarification", clarificationReasoning));
                String researchGoal = clarificationPhase.mergeAnswers(ctx, answers);
                publisher.publish(AgentEvent.reasoningCompleted(
                        taskId, "clarification", clarificationReasoning, 0));
                ctx.transitionTo(TaskState.RUNNING);  // WAITING → RUNNING

                // 继续 Phase 2-4；WAITING 恢复后保持 RUNNING，规划阶段在执行上下文中完成。
                publisher.publish(AgentEvent.progress(taskId, 0, 0, "正在制定研究计划..."));

                var planningReasoning = ReasoningOptionsSupport.resolve(ctx, StepType.PLANNING);
                publisher.publish(AgentEvent.reasoningStarted(
                        taskId, "research-planning", planningReasoning));
                TaskPlan plan = researchPlanner.plan(researchGoal, ctx);
                publisher.publish(AgentEvent.reasoningCompleted(
                        taskId, "research-planning", planningReasoning, 0));
                ctx.setPlan(plan);

                IterativeExecutionResult execResult = iterativeExecutor.execute(ctx);
                if (!execResult.success()) {
                    sink.error(new RuntimeException("Research failed: " + execResult.error()));
                    return;
                }

                var synthesisReasoning = ReasoningOptionsSupport.resolve(ctx, StepType.SYNTHESIS);
                publisher.publish(AgentEvent.reasoningStarted(
                        taskId, "research-synthesis", synthesisReasoning));
                String report = synthesize(ctx);
                publisher.publish(AgentEvent.reasoningCompleted(
                        taskId, "research-synthesis", synthesisReasoning, 0));
                ctx.putVariable("finalOutput", report);
                StepArtifactStore.ArtifactRef finalRef =
                        ctx.saveStepArtifact("result", ctx.getPlan().totalSteps(), report);
                verifyFinalArtifact(ctx, finalRef);
                ctx.addArtifact(Artifact.of("research-report.md", "markdown", report, "synthesis"));
                sink.complete();

            } catch (Exception e) {
                log.error("[DEEP_RESEARCH] resume failed taskId={}", taskId, e);
                sink.error(e);
            }
        });
    }

    @Override
    public boolean resumable() {
        return true;
    }

    private void verifyFinalArtifact(AgentExecutionContext ctx,
                                     StepArtifactStore.ArtifactRef finalRef) {
        if (finalRef == null) {
            throw new IllegalStateException("Final research artifact was not stored");
        }
        StepArtifactStore.Verification verification = ctx.verifyStepArtifact(finalRef);
        if (verification == null || !verification.verified()) {
            throw new IllegalStateException("Final research artifact readback verification failed: "
                    + (verification != null ? verification.error() : "verification unavailable"));
        }
        ctx.putVariable("finalArtifactKey", finalRef.objectKey());
        ctx.putVariable("finalArtifactUrl", finalRef.url());
        ctx.putVariable("finalArtifactName", finalRef.name());
        ctx.putVariable("finalArtifactSha256", verification.sha256());
        ctx.putVariable("finalArtifactSize", verification.size());
        ctx.putVariable("finalArtifactVerified", Boolean.TRUE);
    }

    private TaskPlan resetInterruptedSteps(TaskPlan plan) {
        List<TaskStep> steps = plan.steps().stream()
                .map(step -> step.status() == StepStatus.FAILED || step.status() == StepStatus.RUNNING
                        ? step.withStatus(StepStatus.PENDING)
                        : step)
                .toList();
        return new TaskPlan(plan.goal(), steps);
    }

    @Override
    public int priority() {
        return 100;
    }

    // === Phase 4: Synthesis ===

    private String synthesize(AgentExecutionContext ctx) {
        StringBuilder findings = new StringBuilder();
        TaskPlan plan = ctx.getPlan();
        if (plan != null) {
            for (TaskStep step : plan.steps()) {
                if (step.isCompleted() && step.output() != null) {
                    findings.append("### ").append(step.description()).append("\n");
                    findings.append(step.output()).append("\n\n");
                }
            }
        }

        List<ResearchSynthesisPolicy.SourceRef> sources = synthesisPolicy.collectSources(ctx);

        String prompt = """
                你是一个研究报告撰写专家。根据以下研究发现生成结构化研究报告。

                研究目标：%s

                已验证来源（引用链接只能来自这里，不得编造或使用列表之外的链接）：
                %s

                研究发现：
                %s

                报告要求：
                1. 结构：摘要 → 核心发现 → 详细分析 → 结论与建议
                2. 每个关键结论标注信息来源和置信度（高/中/低），引用时使用上面已验证来源中的 URL
                3. 识别信息缺口和局限性
                4. 语言：专业但易读
                """.formatted(ctx.getGoal(), synthesisPolicy.formatSources(sources), findings.toString());

        String report = callWithConfig(ctx, prompt);

        ResearchSynthesisPolicy.Verdict verdict = synthesisPolicy.verify(report, sources);
        if (!verdict.clean()) {
            log.warn("[DEEP_RESEARCH] taskId={} report cites {} unverified url(s), sanitized",
                    ctx.getTaskId(), verdict.foreignUrls().size());
        }
        String grounded = verdict.sanitizedReport();
        if (!sources.isEmpty()) {
            ClaimCitationAnalyzer.Analysis analysis = ClaimCitationAnalyzer.analyze(grounded);
            if (!analysis.meets(0.90)) {
                String repairPrompt = """
                        请修复下面的研究报告。保持原有结论和结构，但让每个事实性段落在本段内引用一个已验证来源 URL，
                        事实 Claim 的引用覆盖率必须至少达到 90%。只能使用给定来源，不得编造链接。只输出 Markdown 报告。

                        已验证来源：
                        %s

                        待修复报告：
                        %s
                        """.formatted(synthesisPolicy.formatSources(sources), grounded);
                String repaired = callWithConfig(ctx, repairPrompt);
                ResearchSynthesisPolicy.Verdict repairedVerdict = synthesisPolicy.verify(repaired, sources);
                grounded = repairedVerdict.sanitizedReport();
                analysis = ClaimCitationAnalyzer.analyze(grounded);
            }
            ctx.putVariable("quality.claimCount", analysis.claimCount());
            ctx.putVariable("quality.supportedClaimCount", analysis.supportedClaimCount());
            ctx.putVariable("quality.claimCitationCoverage", analysis.coverage());
            ctx.putVariable("quality.verifiedSourceCount", sources.size());
            if (!analysis.meets(0.90)) {
                throw new IllegalStateException("Research report claim citation coverage below 90%");
            }
            ClaimEvidenceQualityJudge.Quality quality = evaluateEvidenceQuality(grounded, sources);
            if (quality == null) {
                throw new IllegalStateException("Research report claim evidence judge unavailable");
            }
            ctx.putVariable("quality.faithfulness", quality.faithfulness());
            ctx.putVariable("quality.evidenceSupportRate", quality.evidenceSupportRate());
            ctx.putVariable("quality.sourceTrustScore", quality.sourceTrustScore());
            ctx.putVariable("quality.freshnessScore", quality.freshnessScore());
            ctx.putVariable("quality.conflictRate", quality.conflictRate());
            ctx.putVariable("quality.unsupportedFactRate", quality.unsupportedFactRate());
            ctx.putVariable("quality.groundingStatus", quality.passes() ? "PASSED" : "REJECTED");
            if (!quality.passes()) {
                throw new IllegalStateException("Research report claim evidence quality below threshold");
            }
        }
        return grounded;
    }

    private ClaimEvidenceQualityJudge.Quality evaluateEvidenceQuality(
            String report, List<ResearchSynthesisPolicy.SourceRef> sources) {
        if (claimEvidenceQualityJudge == null) return null;
        List<ClaimEvidenceQualityJudge.EvidenceSource> evidence = sources.stream()
                .map(source -> new ClaimEvidenceQualityJudge.EvidenceSource(
                        source.title(), source.url(), source.source(),
                        source.snippet(), source.publishedDate()))
                .toList();
        return claimEvidenceQualityJudge.evaluate(report, evidence);
    }

    private String callWithConfig(AgentExecutionContext ctx, String userPrompt) {
        Object sessionContext = ctx.getVariable("sessionContext");
        if (sessionContext != null && !sessionContext.toString().isBlank()) {
            userPrompt = sessionContext + "\n\n" + userPrompt;
        }
        String sp = ctx.getVariable("systemPrompt") instanceof String s && !s.isBlank() ? s : null;
        Double temp = ctx.getVariable("temperature") instanceof Double t ? t : null;
        var executionOptions = ctx.getVariable("modelExecutionOptions")
                instanceof com.yin.ai.platform.gateway.ModelExecutionOptions value ? value : null;
        return chatClient.generate("agent:deep-research", userPrompt, sp, temp, executionOptions)
                .getContent();
    }
}
