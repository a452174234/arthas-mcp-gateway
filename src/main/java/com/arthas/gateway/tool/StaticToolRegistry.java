package com.arthas.gateway.tool;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 静态工具注册表（宪法原则二：透明无损聚合）。
 *
 * <p>启动期构建不可变 35 工具快照（31 arthas + 4 网关自有），作为 {@code tools/list} 的单一填充源，
 * 不依赖后端动态发现。arthas 工具 schema 逐字摘抄自 {@code arthas-tools.json}（单一事实源），
 * 网关仅注入顶层 {@code target} 参数（string, required, 进 properties 与 required 数组首位）。
 *
 * <p>不变量：构造后 tools() 返回不可变列表，每个工具的 inputSchema 深冻结。
 */
public final class StaticToolRegistry {

    /** 注入到每个 arthas 工具的顶层参数名。 */
    public static final String TARGET_PARAM = "target";

    /** target 参数的 schema 片段（见 server-contract.md §4 示例）。 */
    private static final Map<String, Object> TARGET_SCHEMA = Map.of(
            "type", "string",
            "description", "目标 JVM 逻辑名（见 list-targets），决定路由到哪个 arthas 后端");

    private final List<ExposedTool> tools;
    private final Map<String, ExposedTool> byName;

    /** 由已构建的 ExposedTool 列表构造（做不可变拷贝）。 */
    public StaticToolRegistry(List<ExposedTool> tools) {
        this.tools = List.copyOf(tools);
        Map<String, ExposedTool> map = new LinkedHashMap<>();
        for (ExposedTool t : this.tools) {
            map.put(t.name(), t);
        }
        this.byName = Map.copyOf(map);
    }

    /** 全部 35 个工具的不可变快照。 */
    public List<ExposedTool> tools() {
        return tools;
    }

    /** 按工具名查找；未知工具返回 empty。 */
    public Optional<ExposedTool> find(String name) {
        return Optional.ofNullable(byName.get(name));
    }

    /**
     * 从 classpath 资源加载 31 个 arthas 工具 schema，注入 target，追加 4 个网关自有工具，
     * 构建不可变 35 工具注册表。
     *
     * @param resourcePath classpath 资源路径（如 {@code "arthas-tools.json"}）
     */
    public static StaticToolRegistry fromClasspath(String resourcePath) {
        try (InputStream in = StaticToolRegistry.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new IllegalStateException("未找到 classpath 工具 schema 资源：" + resourcePath);
            }
            ObjectMapper mapper = new ObjectMapper();
            Map<String, Object> root = mapper.readValue(in, new TypeReference<Map<String, Object>>() {});
            List<Map<String, Object>> specs = asList(root.get("tools"));
            List<ExposedTool> exposed = new ArrayList<>(specs.size() + 4);
            for (Map<String, Object> spec : specs) {
                exposed.add(buildArthasTool(spec));
            }
            exposed.addAll(gatewayTools());
            return new StaticToolRegistry(exposed);
        } catch (IOException e) {
            throw new IllegalStateException("加载工具注册表失败：" + resourcePath, e);
        }
    }

    /** 注入 target 到 arthas 原始 schema，构造 arthas 工具。 */
    @SuppressWarnings("unchecked")
    private static ExposedTool buildArthasTool(Map<String, Object> spec) {
        Map<String, Object> schema = deepMutable((Map<String, Object>) spec.get("inputSchema"));

        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
        if (properties == null) {
            properties = new LinkedHashMap<>();
        } else {
            properties = deepMutable(properties);
        }
        properties.put(TARGET_PARAM, new LinkedHashMap<>(TARGET_SCHEMA));
        schema.put("properties", properties);

        List<String> required = new ArrayList<>();
        Object rawRequired = schema.get("required");
        if (rawRequired instanceof List<?> list) {
            for (Object r : list) {
                required.add((String) r);
            }
        }
        required.add(0, TARGET_PARAM);
        schema.put("required", required);

        return new ExposedTool(
                (String) spec.get("name"),
                (String) spec.get("description"),
                schema,
                TaskSupport.fromWire((String) spec.get("taskSupport")),
                RoutingMode.valueOf((String) spec.get("routingMode")));
    }

    /** 4 个网关自有工具（routingMode=GATEWAY_LOCAL，不转发后端）。schema 见 gateway-tools-contract.md。 */
    private static List<ExposedTool> gatewayTools() {
        return List.of(
                new ExposedTool(
                        "arthas-gateway.list-targets",
                        "列出网关当前注册的所有诊断目标（逻辑名/健康状态/协议），供 target 参数取值参考",
                        schema(Map.of(), List.of()),
                        null,
                        RoutingMode.GATEWAY_LOCAL),
                new ExposedTool(
                        "arthas-gateway.task-get",
                        "查询一个异步诊断任务（watch/trace/stack/tt/monitor 触发）的状态；完成时返回最终结果",
                        schema(Map.of("taskId", Map.of(
                                "type", "string",
                                "description", "异步工具调用返回的 taskId")), List.of("taskId")),
                        null,
                        RoutingMode.GATEWAY_LOCAL),
                new ExposedTool(
                        "arthas-gateway.task-list",
                        "列出当前所有异步诊断任务的概要（状态总览）",
                        schema(Map.of("status", Map.of(
                                "type", "string",
                                "description", "可选过滤：working|completed|failed|cancelled；不传则全部")), List.of()),
                        null,
                        RoutingMode.GATEWAY_LOCAL),
                new ExposedTool(
                        "arthas-gateway.task-cancel",
                        "取消一个仍在 working 的异步诊断任务",
                        schema(Map.of("taskId", Map.of(
                                "type", "string",
                                "description", "要取消的 taskId")), List.of("taskId")),
                        null,
                        RoutingMode.GATEWAY_LOCAL));
    }

    /** 构造标准 inputSchema 骨架。 */
    private static Map<String, Object> schema(Map<String, Object> properties, List<String> required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", new LinkedHashMap<>(properties));
        schema.put("required", new ArrayList<>(required));
        schema.put("additionalProperties", false);
        return schema;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> deepMutable(Map<String, Object> src) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (src == null) {
            return out;
        }
        src.forEach((k, v) -> out.put(k, switch (v) {
            case Map<?, ?> m -> deepMutable((Map<String, Object>) m);
            case List<?> l -> new ArrayList<>(l);
            default -> v;
        }));
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> asList(Object raw) {
        if (raw instanceof List<?> list) {
            List<Map<String, Object>> out = new ArrayList<>();
            for (Object o : list) {
                out.add((Map<String, Object>) o);
            }
            return out;
        }
        throw new IllegalStateException("arthas-tools.json 的 tools 字段须为数组");
    }
}
