package com.arthas.gateway.backend;

/**
 * 后端 MCP 协议（data-model.md §2）。
 *
 * <p>取值源自 arthas 后端接入契约：
 * <ul>
 *   <li>{@link #STREAMABLE}：有状态会话（Mcp-Session-Id），支持异步任务；arthas 默认形态。</li>
 *   <li>{@link #STATELESS}：无状态，纯 JSON 一来一回，不支持任务（见 C-STATELESS-1）。</li>
 * </ul>
 * 决定是否可对该后端发起异步任务（STATELESS 后端的 optional 工具不可走 ASYNC_TASK）。
 */
public enum Protocol {
    STREAMABLE,
    STATELESS
}
