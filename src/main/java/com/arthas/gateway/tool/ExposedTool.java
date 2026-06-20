package com.arthas.gateway.tool;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 对调用方暴露的单个工具（data-model.md §5）。
 *
 * <p>不可变值对象。统一承载两类工具：
 * <ul>
 *   <li>arthas 工具（31）：inputSchema 为 arthas 原始 schema + 注入的 {@code target}；taskSupport 非 null。</li>
 *   <li>网关自有工具（4）：routingMode=GATEWAY_LOCAL，taskSupport=null。</li>
 * </ul>
 * inputSchema 在构造时做深冻结，防止外部修改泄漏到 {@code tools/list} 快照。
 */
public record ExposedTool(
        String name,
        String description,
        Map<String, Object> inputSchema,
        TaskSupport taskSupport,
        RoutingMode routingMode) {

    public ExposedTool {
        Objects.requireNonNull(name, "name 不可为空");
        Objects.requireNonNull(description, "description 不可为空");
        Objects.requireNonNull(routingMode, "routingMode 不可为空");
        inputSchema = Map.copyOf(deepImmutable(Objects.requireNonNull(
                inputSchema, "inputSchema 不可为空")));
    }

    /** 是否为网关自有工具（不转发后端）。 */
    public boolean gatewayOwned() {
        return routingMode == RoutingMode.GATEWAY_LOCAL;
    }

    /** 将任意 JSON 树深冻结为不可变结构（递归 Map→unmodifiable / List→unmodifiable）。 */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> deepImmutable(Map<String, Object> src) {
        Map<String, Object> out = new LinkedHashMap<>();
        src.forEach((k, v) -> out.put(k, switch (v) {
            case Map<?, ?> m -> Map.copyOf(deepImmutable(toStrMap(m)));
            case List<?> l -> List.copyOf(deepImmutableList(l));
            default -> v;
        }));
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> deepImmutableList(List<?> src) {
        List<Object> out = new ArrayList<>();
        for (Object v : src) {
            out.add(switch (v) {
                case Map<?, ?> m -> Map.copyOf(deepImmutable(toStrMap(m)));
                case List<?> l -> List.copyOf(deepImmutableList(l));
                default -> v;
            });
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> toStrMap(Map<?, ?> m) {
        Map<String, Object> out = new LinkedHashMap<>();
        m.forEach((k, v) -> out.put((String) k, v));
        return out;
    }
}
