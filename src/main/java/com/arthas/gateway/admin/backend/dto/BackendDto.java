package com.arthas.gateway.admin.backend.dto;

/**
 * 004 后端配置 CRUD 响应 DTO（admin-api-contract §1，data-model §1.1）。
 *
 * <p>后端注册表的只读投影（源 {@code BackendRegistry}+{@code BackendEntry}）。凭据脱敏：
 * <b>仅暴露 {@code authMode}，不含 token/username/password</b>（admin-invariants INV-SECRET-1）。
 *
 * <p>枚举字段用 String（DTO 边界、序列化友好，前端直接消费）：
 * source=STATIC|DYNAMIC、state=ACTIVE|RETIRED、breaker=OPEN|CLOSED、protocol=STREAMABLE|STATELESS、authMode=NONE|BEARER|BASIC。
 */
public record BackendDto(
        String name,
        String source,
        String state,
        boolean healthy,
        String breaker,
        String url,
        String protocol,
        String authMode,
        int connectTimeoutMs,
        int callTimeoutMs,
        int maxConcurrentTasks,
        String k8sHost,
        String pod,
        String namespace,
        String sourceDetail,
        String ensureStatus) {
}
