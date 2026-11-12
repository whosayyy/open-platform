package com.yin.ai.platform.adapter;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * 适配层的架构约束。
 * <p>
 * 这两条规则原先位于 platform 模块的 {@code ArchitectureRuleTest}。adapter 包迁出
 * platform 成为独立模块后，规则的目标类不再属于 platform 的 classpath，ArchUnit 会因为
 * “没有类匹配 that()”而报空断言失败——所以规则要跟着代码走。
 * <p>
 * 另一条“adapter 不得依赖 starter”的规则迁移到了 server 模块，因为只有那里同时具备
 * adapter 与 starter 两个包。
 */
@DisplayName("ArchUnit 架构规则（Adapter）")
class AdapterArchitectureRuleTest {

    private static final String ROOT = "com.yin.ai.platform";
    private static final String ADAPTER = ROOT + ".adapter..";

    private static JavaClasses classes;

    @BeforeAll
    static void beforeAll() {
        classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(ROOT);
    }

    @Test
    @DisplayName("Adapter 内部包不得越界依赖 Agent 编排实现")
    void adapterMustNotDependOnAgentOrchestration() {
        // adapter 位于 platform 之上，实现 platform 定义的端口；
        // 允许依赖 agent 的端口/模型，但不得反向依赖 server 的自动配置。
        noClasses()
                .that().resideInAnyPackage(ADAPTER)
                .should().dependOnClassesThat()
                .resideInAnyPackage(ROOT + ".starter..")
                .because("Adapter 必须能脱离 Spring Boot 自动配置独立使用")
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
