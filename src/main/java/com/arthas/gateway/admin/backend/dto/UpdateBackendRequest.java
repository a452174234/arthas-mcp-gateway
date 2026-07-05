package com.arthas.gateway.admin.backend.dto;

/**
 * 004 修改后端请求体（PUT /admin/backends/{name}）。name 在 path，不在 body。
 *
 * <p>仅静态后端可改（动态后端 PUT 拒绝，R3）。可空字段（null=不改）。url/auth/超时/并发可部分更新。
 */
public record UpdateBackendRequest(
        String url,
        String authMode,
        String token,
        String username,
        String password,
        Integer connectTimeoutMs,
        Integer callTimeoutMs,
        Integer maxConcurrentTasks) {
}
