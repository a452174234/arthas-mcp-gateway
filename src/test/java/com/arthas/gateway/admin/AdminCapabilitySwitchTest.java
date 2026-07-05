package com.arthas.gateway.admin;

import com.arthas.gateway.admin.backend.BackendCrudAutoConfig;
import com.arthas.gateway.admin.task.TaskExportAutoConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 004 管理面能力开关契约（admin-invariants INV-SWITCH-1/2，T005）。
 *
 * <p>验证 {@code @ConditionalOnProperty}：后端 CRUD 与任务导出各自独立装配，
 * 默认开（matchIfMissing=true）、关闭则配置类不装配（对应 /admin 端点 404、前端降级）。
 * 用 {@link ApplicationContextRunner} 轻量评估条件（不启完整 Spring 上下文）。
 *
 * <p>Phase 3/4 在 AutoConfig 内注册 Controller @Bean 后，关闭开关 → Controller 不在 → 端点 404。
 */
class AdminCapabilitySwitchTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner();

    @Test
    void crudSwitchDefaultEnabled_loadsBackendCrudAutoConfig() {
        runner.withUserConfiguration(BackendCrudAutoConfig.class)
                .run(ctx -> assertThat(ctx).hasSingleBean(BackendCrudAutoConfig.class));
    }

    @Test
    void crudSwitchDisabled_skipsBackendCrudAutoConfig() {
        runner.withUserConfiguration(BackendCrudAutoConfig.class)
                .withPropertyValues("arthas-gateway.admin.crud.enabled=false")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(BackendCrudAutoConfig.class));
    }

    @Test
    void exportSwitchDefaultEnabled_loadsTaskExportAutoConfig() {
        runner.withUserConfiguration(TaskExportAutoConfig.class)
                .run(ctx -> assertThat(ctx).hasSingleBean(TaskExportAutoConfig.class));
    }

    @Test
    void exportSwitchDisabled_skipsTaskExportAutoConfig() {
        runner.withUserConfiguration(TaskExportAutoConfig.class)
                .withPropertyValues("arthas-gateway.admin.export.enabled=false")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(TaskExportAutoConfig.class));
    }

    @Test
    void crudAndExportSwitchesIndependent() {
        // INV-SWITCH-1：关 crud、开 export → crud 不装配、export 装配
        runner.withUserConfiguration(BackendCrudAutoConfig.class, TaskExportAutoConfig.class)
                .withPropertyValues("arthas-gateway.admin.crud.enabled=false")
                .run(ctx -> {
                    assertThat(ctx).doesNotHaveBean(BackendCrudAutoConfig.class);
                    assertThat(ctx).hasSingleBean(TaskExportAutoConfig.class);
                });
    }

    @Test
    void bothSwitchesDisabled_skipsBoth() {
        runner.withUserConfiguration(BackendCrudAutoConfig.class, TaskExportAutoConfig.class)
                .withPropertyValues(
                        "arthas-gateway.admin.crud.enabled=false",
                        "arthas-gateway.admin.export.enabled=false")
                .run(ctx -> {
                    assertThat(ctx).doesNotHaveBean(BackendCrudAutoConfig.class);
                    assertThat(ctx).doesNotHaveBean(TaskExportAutoConfig.class);
                });
    }
}
