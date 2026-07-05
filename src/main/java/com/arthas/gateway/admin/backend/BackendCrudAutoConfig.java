package com.arthas.gateway.admin.backend;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;

/**
 * 004 后端配置 CRUD 能力的条件装配（research.md R9 / spec FR-014）。
 *
 * <p>{@code @ConditionalOnProperty(admin.crud.enabled, matchIfMissing=true)}：默认开；
 * {@code arthas-gateway.admin.crud.enabled=false} 时本配置类不装配 →
 * /admin/backends 端点不暴露（404）、前端降级提示（admin-invariants INV-SWITCH-1）。
 *
 * <p>Phase 3 在此注册 {@code BackendAdminController} 等 bean（@Bean 方法）。
 */
@Configuration
@ConditionalOnProperty(name = "arthas-gateway.admin.crud.enabled", havingValue = "true", matchIfMissing = true)
public class BackendCrudAutoConfig {
}
