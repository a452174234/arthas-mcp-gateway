package com.arthas.gateway.backend;

/**
 * 后端认证模式（data-model.md §2 {@code auth.mode}）。
 *
 * <ul>
 *   <li>{@link #NONE}：无认证（MVP 受控内网常见）。</li>
 *   <li>{@link #BEARER}：Bearer token（token == 后端 password，见
 *       {@code reference/arthas-docs/03-MCP/后端接入契约.md} §2.3）。</li>
 *   <li>{@link #BASIC}：HTTP Basic（{@code base64(user:pass)}）。</li>
 * </ul>
 * 认证头注入由 {@code auth/BackendAuthCustomizer}（T023）按此模式构造。
 */
public enum AuthMode {
    NONE,
    BEARER,
    BASIC
}
