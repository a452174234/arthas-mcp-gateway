package com.arthas.gateway.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * T030 包级边界守护（003 特性，research.md R1/R6：gateway-core 诊断核心<b>零 K8S 感知</b>）。
 *
 * <p>宪法原则二 + R6 内聚性纪律：K8S 编排是<b>独立编排面</b>，仅 {@code orchestration} 包 +
 * {@code config} 组合根可触及 K8S；诊断核心（backend / handler / tool / task / auth / obs）<b>不得</b>
 * 依赖编排包或 K8S 客户端 API。3 个编排工具的 handler 自带闭包、<b>不经 {@code ToolsCallRouter}</b>
 * （路由器零 K8S 分支），故 {@code tools/list}=38 与 kubeconfig 是否存在无关（回归守护 T029）。
 *
 * <p>本测试为<b>纯逻辑 surefire</b>（CI 可跑，无 K8S 依赖）：ArchUnit 静态扫描 {@code com.arthas.gateway..}
 * 主代码字节码，断言诊断核心包的依赖方向。{@code config} 包（组合根，装配 orchestration bean）刻意<b>不在</b>
 * 禁止范围——边界只锁诊断核心（见 {@code K8sOrchestrationConfig} 类注释）。
 *
 * <p>防止未来回归：任何在诊断核心包内引入 {@code orchestration} 或 {@code io.fabric8} 的提交都会被本测试拦截。
 */
class PackageBoundaryTest {

    /** gateway-core 诊断核心包（K8S 编排隔离边界内的"洁浄区"）。 */
    private static final String[] DIAGNOSTIC_CORE = {
            "com.arthas.gateway.backend..",
            "com.arthas.gateway.handler..",
            "com.arthas.gateway.tool..",
            "com.arthas.gateway.task..",
            "com.arthas.gateway.auth..",
            "com.arthas.gateway.obs.."
    };

    private static JavaClasses classes;

    @BeforeAll
    static void importGatewayClasses() {
        // 仅导入 com.arthas.gateway.. 主代码（排除测试类），分析其依赖方向；不导入外部 jar
        classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.arthas.gateway..");
    }

    /** 诊断核心包不得依赖 orchestration 包（K8S 编排隔离，R1/R6）。ArchUnit 否定式：noClasses()...should().dependOn()。 */
    @Test
    void diagnosticCoreDoesNotDependOnOrchestration() {
        noClasses()
                .that().resideInAnyPackage(DIAGNOSTIC_CORE)
                .should().dependOnClassesThat().resideInAPackage("com.arthas.gateway.orchestration..")
                .because("gateway-core 诊断核心零 K8S 依赖（包级边界，R1/R6）；"
                        + "K8S 编排仅 orchestration 包 + config 组合根可触及")
                .check(classes);
    }

    /** 诊断核心包不得直接依赖 fabric8/kubernetes-client API（K8S 客户端仅 orchestration 包使用）。 */
    @Test
    void diagnosticCoreDoesNotDependOnK8sClientApi() {
        noClasses()
                .that().resideInAnyPackage(DIAGNOSTIC_CORE)
                .should().dependOnClassesThat().resideInAnyPackage("io.fabric8..", "io.kubernetes..")
                .because("fabric8/kubernetes-client API 仅 orchestration 包使用（R6 内聚性纪律）；"
                        + "诊断核心经既有 MCP 路由管线，不直接操 K8S")
                .check(classes);
    }
}
