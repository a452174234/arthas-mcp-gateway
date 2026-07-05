package com.arthas.gateway.admin.backend.dto;

/**
 * 004 新增后端请求体（POST /admin/backends）。仅静态后端可手动新增（动态后端由 003 ensure 产生，POST 拒绝，R3）。
 *
 * <p>缺省值（与 {@code BackendConfigLoader} 一致）：protocol=STREAMABLE、authMode=NONE、
 * connectTimeoutMs=5000、callTimeoutMs=30000、maxConcurrentTasks=5。可空字段（Integer/String）= 缺省。
 * name+url 必填，由后端 {@code BackendAdminService} 校验。
 */
public record CreateBackendRequest(
        String name,
        String url,
        String protocol,
        String authMode,
        String token,
        String username,
        String password,
        Integer connectTimeoutMs,
        Integer callTimeoutMs,
        Integer maxConcurrentTasks) {
}
