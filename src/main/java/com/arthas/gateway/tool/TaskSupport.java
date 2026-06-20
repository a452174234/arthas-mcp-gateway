package com.arthas.gateway.tool;

import java.util.Locale;

/**
 * 工具任务支持度（data-model.md §5.1，对应 MCP {@code execution.taskSupport}）。
 *
 * <p>JSON 线值为小写 {@code forbidden/optional/required}（MCP能力清单 §0.6）。
 * 仅 31 个 arthas 工具携带；4 个网关自有工具为 {@code null}（不暴露 taskSupport）。
 *
 * <ul>
 *   <li>{@link #FORBIDDEN}：26 个（25 SYNC_DIRECT + dashboard STREAM_AGGREGATE）</li>
 *   <li>{@link #OPTIONAL}：5 个（watch/trace/stack/tt/monitor，方案 C 异步任务）</li>
 *   <li>{@link #REQUIRED}：0（arthas 无必须任务工具）</li>
 * </ul>
 */
public enum TaskSupport {
    FORBIDDEN,
    OPTIONAL,
    REQUIRED;

    /** 从 JSON 线值（如 "forbidden"）解析为枚举。 */
    public static TaskSupport fromWire(String wire) {
        if (wire == null || wire.isBlank()) {
            throw new IllegalArgumentException("taskSupport 不可为空");
        }
        return TaskSupport.valueOf(wire.trim().toUpperCase(Locale.ROOT));
    }
}
