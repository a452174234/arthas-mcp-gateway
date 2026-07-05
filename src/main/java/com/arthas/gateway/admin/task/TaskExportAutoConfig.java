package com.arthas.gateway.admin.task;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;

/**
 * 004 异步任务结果导出能力的条件装配（research.md R9 / spec FR-014）。
 *
 * <p>{@code @ConditionalOnProperty(admin.export.enabled, matchIfMissing=true)}：默认开；
 * {@code arthas-gateway.admin.export.enabled=false} 时本配置类不装配 →
 * /admin/tasks/{id}/export 端点不暴露（404）、前端降级（admin-invariants INV-SWITCH-2）。
 *
 * <p>Phase 4 在此注册 {@code TaskExportController} 等 bean。
 */
@Configuration
@ConditionalOnProperty(name = "arthas-gateway.admin.export.enabled", havingValue = "true", matchIfMissing = true)
public class TaskExportAutoConfig {
}
