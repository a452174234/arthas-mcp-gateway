package com.arthas.gateway.tool;

/**
 * 工具路由模式（data-model.md §5.1，内部字段，不进 MCP 协议）。
 *
 * <p>分类源自 {@code reference/arthas-docs/03-MCP/工具传输分类表.md}：
 * <ul>
 *   <li>{@link #SYNC_DIRECT}：26 个即时工具（非流式、taskSupport=forbidden），同步转发、原样透传。</li>
 *   <li>{@link #STREAM_AGGREGATE}：dashboard（流式 + forbidden），网关聚合 SSE 多帧为一次结果。</li>
 *   <li>{@link #ASYNC_TASK}：5 个 optional 工具（watch/trace/stack/tt/monitor），立即返回 taskId，方案 C 后台阻塞等后端。</li>
 *   <li>{@link #GATEWAY_LOCAL}：4 个网关自有工具（list-targets/task-get/task-list/task-cancel），不转发后端。</li>
 * </ul>
 */
public enum RoutingMode {
    SYNC_DIRECT,
    STREAM_AGGREGATE,
    ASYNC_TASK,
    GATEWAY_LOCAL
}
