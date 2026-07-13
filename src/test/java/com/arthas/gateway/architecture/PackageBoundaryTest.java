package com.arthas.gateway.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
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

    /**
     * 004 增量：诊断核心不得依赖管理面（admin 包，admin-invariants INV-ISOL-1）。
     *
     * <p>admin 管理面消费 backend（CRUD/导出），反向依赖禁止——诊断核心（/mcp）不被管理面（/admin）污染，
     * 保证管理面操作不影响诊断面（回归守护 SC-004）。
     */
    @Test
    void diagnosticCoreDoesNotDependOnAdmin() {
        noClasses()
                .that().resideInAnyPackage(DIAGNOSTIC_CORE)
                .should().dependOnClassesThat().resideInAPackage("com.arthas.gateway.admin..")
                .because("诊断核心（/mcp）与管理面（/admin）隔离（004 INV-ISOL-1/SC-004）；"
                        + "admin 消费 backend，反向依赖禁止")
                .check(classes);
    }

    /**
     * 005 US2 INV-BOUNDARY-1：{@code BackendResolver} 接口 gateway-core 定义（backend 包，零 fabric8/orchestration 依赖）。
     *
     * <p>接口倒置——诊断核心依赖 backend.BackendResolver（零 K8S），实现在 orchestration（K8sBackendResolver）。
     * 被 {@link #diagnosticCoreDoesNotDependOnK8sClientApi}（backend 包零 fabric8）覆盖，本规则显式锁定接口位置。
     */
    @Test
    void backendResolverInterfaceResidesInBackendPackage() {
        classes().that().haveSimpleName("BackendResolver")
                .should().resideInAPackage("com.arthas.gateway.backend")
                .because("005 INV-BOUNDARY-1: BackendResolver 接口 gateway-core 定义（backend 包，零 fabric8）；"
                        + "实现在 orchestration（K8sBackendResolver），ArchUnit 锁定接口位置防漂移")
                .check(classes);
    }

    /**
     * 005 US2 INV-BOUNDARY-2：{@code K8sBackendResolver} 实现驻 orchestration 包（依赖 fabric8/ArthasProvisioner）。
     *
     * <p>确保 K8S 懒 resolve 实现（持编排依赖）不误放 gateway-core；与 BackendResolver 接口（backend 包）的倒置分离。
     */
    @Test
    void k8sBackendResolverResidesInOrchestration() {
        classes().that().haveSimpleName("K8sBackendResolver")
                .should().resideInAPackage("com.arthas.gateway.orchestration")
                .because("005 INV-BOUNDARY-2: K8sBackendResolver 实现在 orchestration 包（依赖 fabric8/"
                        + "ArthasProvisioner），不漂入 gateway-core 破零 K8S 依赖")
                .check(classes);
    }

    /**
     * 006 波7 INV-BOUNDARY-3：诊断核心不得依赖 SSH 库（sshj：{@code net.schmizz.sshj} / {@code com.hierynomus}）。
     *
     * <p>SSH 引导（{@code SshKubeconfigFetcher} 等）仅 orchestration 包使用；诊断核心零 SSH 依赖，防止 gateway-core
     * 被 SSH 库污染（与 fabric8 守护同理）。
     */
    @Test
    void diagnosticCoreDoesNotDependOnSshj() {
        noClasses()
                .that().resideInAnyPackage(DIAGNOSTIC_CORE)
                .should().dependOnClassesThat().resideInAnyPackage("net.schmizz..", "com.hierynomus..")
                .because("006 INV-BOUNDARY-3: sshj SSH 库仅 orchestration 包使用（SshKubeconfigFetcher）；"
                        + "诊断核心零 SSH 依赖，gateway-core 不被 SSH 污染")
                .check(classes);
    }
}
