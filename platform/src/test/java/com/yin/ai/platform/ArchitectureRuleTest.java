package com.yin.ai.platform;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * 架构规则测试 — 强制执行 V2.0 分层准入控制体系。
 * <p>
 * 规则来自 02-V2-Architecture.md §11。
 */
@DisplayName("ArchUnit 架构规则")
class ArchitectureRuleTest {

    private static final String ROOT = "com.yin.ai.platform";
    private static final String CORE = ROOT + ".core..";
    private static final String ADAPTER = ROOT + ".adapter..";
    private static final String STARTER = ROOT + ".starter..";
    private static final String RAG = ROOT + ".rag..";
    private static final String ENGINE = ROOT + ".engine..";
    private static final String GATEWAY_ADAPTER = ROOT + ".gateway.adapter..";
    // 结构化工具生成器 — 底层 LLM 工具调用封装的 sanctioned 层，被 rag/workflow/agent 共同依赖
    private static final String GATEWAY_CONFIG = ROOT + ".gateway.config..";
    // Packages allowed to use LangChain4j Embedding API (but NOT chat model / AiServices)
    private static final String[] EMBEDDING_ALLOWED = {RAG, ROOT + ".knowledge..",
            ROOT + ".router..", ROOT + ".skill..", ROOT + ".function.."};

    private static JavaClasses classes;

    @BeforeAll
    static void beforeAll() {
        classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(ROOT);
    }

    // —— 规则1：平台层零依赖业务模块 ——

    @Test
    @DisplayName("平台层不得依赖业务模块 (wkc-ai-service 等)")
    void platformMustNotDependOnBusinessModules() {
        ArchRule rule = noClasses()
                .should().dependOnClassesThat()
                .resideInAnyPackage(
                        "com.yin.ai.service..",
                        "com.yin.content..",
                        "com.yin.interaction..",
                        "com.yin.notification..",
                        "com.yin.file..",
                        "com.yin.chat..",
                        "com.yin.user.."
                )
                .because("wkc-ai-platform 是纯基础设施层，不允许 import 任何业务模块");
        rule.check(classes);
    }

    // —— 规则2：Spring AI ChatClient 限制在 gateway + agent 包 ——

    @Test
    @DisplayName("Spring AI ChatClient 仅限 Gateway provider adapter/config 使用")
    void onlyGatewayAndAgentCanAccessChatClient() {
        ArchRule rule = noClasses()
                .that().resideOutsideOfPackage(GATEWAY_ADAPTER)
                .and().resideOutsideOfPackage(GATEWAY_CONFIG)
                .should().dependOnClassesThat()
                .resideInAnyPackage("org.springframework.ai.chat.client..")
                .because("原始 ChatClient 只能由 Gateway provider adapter/config 持有；"
                        + "Agent、RAG、Structured 和业务层必须调用统一 Gateway facade");
        rule.check(classes);
    }

    // —— 规则3：LangChain4j Embedding API 允许 router/skill/function 使用，但 LLM 调用全系统禁止 ——

    @Test
    @DisplayName("LangChain4j Embedding 层可跨模块使用，但 LLM 调用 API 全系统禁止")
    void langChain4jLlmApiIsBanned() {
        ArchRule rule = noClasses()
                .should().dependOnClassesThat()
                .resideInAnyPackage(
                        "dev.langchain4j.model.chat..",
                        "dev.langchain4j.service.."
                )
                .because("LangChain4j LLM 调用能力禁用，统一走 AI Gateway；Embedding API 不受此限");
        rule.check(classes);
    }

    // —— 规则4：禁止使用 InheritableThreadLocal ——

    @Test
    @DisplayName("禁止使用 InheritableThreadLocal（用 ExecutionContext 替代）")
    void inheritableThreadLocalIsBanned() {
        ArchRule rule = noClasses()
                .should().dependOnClassesThat()
                .areAssignableTo(InheritableThreadLocal.class)
                .because("InheritableThreadLocal 有内存泄漏风险，使用 ExecutionContext 进行上下文传递");
        rule.check(classes);
    }

    // —— 规则5：Function 核心类不依赖 Router 层 ——

    @Test
    @DisplayName("Function 核心实现不依赖 Router")
    void coreFunctionMustNotDependOnRouter() {
        ArchRule rule = noClasses()
                .that().resideInAnyPackage(ROOT + ".function..")
                .and().resideOutsideOfPackage(ROOT + ".function.config..")
                .should().dependOnClassesThat()
                .resideInAnyPackage(ROOT + ".router..")
                .because("Function 核心实现应保持独立，不依赖 Router 层；config 层除外");
        rule.check(classes);
    }

    @Test
    @DisplayName("Core 包必须保持纯 Java")
    void coreMustRemainFrameworkNeutral() {
        ArchRule rule = noClasses()
                .that().resideInAnyPackage(CORE)
                .should().dependOnClassesThat()
                .resideInAnyPackage(
                        "org.springframework..",
                        "reactor..",
                        "org.redisson..",
                        "jakarta.servlet..",
                        "com.baomidou..",
                        "com.alibaba.nacos..",
                        "com.alibaba.csp.sentinel..",
                        "com.rabbitmq..",
                        "dev.langchain4j..",
                        "io.micrometer..",
                        "io.opentelemetry..",
                        "com.yin.domain..",
                        "com.yin.security..",
                        "com.yin.ai.service.."
                )
                .because("core 是未来可直接抽成 Maven 模块的纯 Java 边界");
        rule.check(classes);
    }

    @Test
    @DisplayName("Core 不得反向依赖 Adapter 或 Starter")
    void coreMustNotDependOnOuterLayers() {
        noClasses()
                .that().resideInAnyPackage(CORE)
                .should().dependOnClassesThat()
                .resideInAnyPackage(ADAPTER, STARTER)
                .because("依赖方向固定为 starter -> adapter -> core")
                .check(classes);
    }

    @Test
    @DisplayName("Core 只能依赖 Core 内部的平台类型")
    void coreMustNotDependOnLegacyPlatformPackages() {
        DescribedPredicate<JavaClass> nonCorePlatformClass = DescribedPredicate.describe(
                "reside in a non-core platform package",
                javaClass -> javaClass.getPackageName().startsWith(ROOT + ".")
                        && !javaClass.getPackageName().startsWith(ROOT + ".core")
        );

        noClasses()
                .that().resideInAnyPackage(CORE)
                .should().dependOnClassesThat(nonCorePlatformClass)
                .because("core 应可作为独立 Maven 模块直接抽离，不能引用遗留平台包")
                .check(classes);
    }

    @Test
    @DisplayName("Adapter 不得依赖 Starter")
    void adaptersMustNotDependOnStarter() {
        noClasses()
                .that().resideInAnyPackage(ADAPTER)
                .should().dependOnClassesThat()
                .resideInAnyPackage(STARTER)
                .because("Adapter 必须能脱离 Spring Boot 自动配置独立使用")
                .check(classes);
    }

    @Test
    @DisplayName("遗留 Agent Task/HITL 包不得新增 Redis 实现")
    void legacyAgentRuntimePortsMustNotOwnRedisImplementations() {
        noClasses()
                .that().resideInAnyPackage(ROOT + ".agent.task..", ROOT + ".agent.hitl..")
                .and().areNotAnnotatedWith(Deprecated.class)
                .should().dependOnClassesThat()
                .resideInAnyPackage("org.redisson..")
                .because("Redis 实现必须位于 adapter.runtime.persistence；旧类只允许保留兼容壳")
                .check(classes);
    }

    @Test
    @DisplayName("遗留 Agent Event 包不得持有 Redis 或 Spring Web 实现")
    void legacyAgentEventPackageMustNotOwnInfrastructure() {
        noClasses()
                .that().resideInAnyPackage(ROOT + ".agent.event..")
                .and().areNotAnnotatedWith(Deprecated.class)
                .should().dependOnClassesThat()
                .resideInAnyPackage(
                        "org.redisson..",
                        "org.springframework.http..",
                        "org.springframework.security..",
                        "org.springframework.web..")
                .because("事件持久化属于 adapter.runtime，SSE/HTTP 属于 adapter.spring；旧类只允许兼容壳")
                .check(classes);
    }

    @Test
    @DisplayName("Runtime Event Adapter 不得依赖 Spring")
    void runtimeEventAdapterMustNotDependOnSpring() {
        noClasses()
                .that().resideInAnyPackage(ROOT + ".adapter.runtime.event..")
                .should().dependOnClassesThat()
                .resideInAnyPackage("org.springframework..")
                .because("Redis 事件持久化与实时分发必须能脱离 Spring Web 使用")
                .check(classes);
    }
}
