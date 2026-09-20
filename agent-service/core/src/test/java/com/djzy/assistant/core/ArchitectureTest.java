package com.djzy.assistant.core;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * 可插拔护栏（§18.11.4 闸二）：core 包不得依赖任何 agent 框架包，也不得依赖 runtime.* 包。
 *
 * <p>闸一是编译期（core 的 pom 不声明框架依赖），这里是架构测试，违规 CI 直接红。
 */
@AnalyzeClasses(packages = "com.djzy.assistant.core")
class ArchitectureTest {

    @ArchTest
    static final ArchRule coreMustNotDependOnAgentFramework =
            noClasses()
                    .that()
                    .resideInAPackage("com.djzy.assistant.core..")
                    .should()
                    .dependOnClassesThat()
                    .resideInAnyPackage("io.agentscope..", "io.agentscope.harness..")
                    .because("换运行时不得影响平台 core（ADR-32）");

    @ArchTest
    static final ArchRule coreMustNotDependOnRuntimeModules =
            noClasses()
                    .that()
                    .resideInAPackage("com.djzy.assistant.core..")
                    .should()
                    .dependOnClassesThat()
                    .resideInAPackage("com.djzy.assistant.runtime..")
                    .because("依赖方向只能是 runtime → agent-spi ← core（§18.11.1）");
}
