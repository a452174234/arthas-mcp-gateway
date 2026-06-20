package com.arthas.gateway.tool;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * StaticToolRegistry 契约测试（T013）。
 * <p>
 * 驱动「加载 arthas-tools.json 31 工具 → 注入 target → 追加 4 网关自有工具 → 不可变 35 快照」，
 * 覆盖 server-contract.md 的 S-TL-2/3/4/5 断言点：
 * <ul>
 *   <li>S-TL-1：工具数 == 35（31 arthas + 4 网关自有）</li>
 *   <li>S-TL-2：每个 arthas 工具 properties 含 target 且 ∈ required；additionalProperties==false</li>
 *   <li>S-TL-3：除 target 外，inputSchema 逐字等于 arthas 原始 schema</li>
 *   <li>S-TL-4：taskSupport 符合预期（dashboard forbidden；watch/trace/stack/tt/monitor optional；其余 forbidden）</li>
 *   <li>routingMode 分布：25 SYNC_DIRECT + 1 STREAM_AGGREGATE(dashboard) + 5 ASYNC_TASK + 4 GATEWAY_LOCAL</li>
 * </ul>
 */
class StaticToolRegistryTest {

    private static StaticToolRegistry registry;
    private static Map<String, Map<String, Object>> rawArthasByName; // 原始 schema 基准（无 target）

    @BeforeAll
    static void loadRegistry() throws Exception {
        registry = StaticToolRegistry.fromClasspath("arthas-tools.json");
        // 独立加载原始 JSON 作 S-TL-3 比对基准（不经 StaticToolRegistry，证明「转换仅新增 target」）
        try (var in = StaticToolRegistryTest.class.getClassLoader().getResourceAsStream("arthas-tools.json")) {
            assertThat(in).as("arthas-tools.json 须存在于 classpath").isNotNull();
            Map<String, Object> root = new ObjectMapper().readValue(in, new TypeReference<Map<String, Object>>() {});
            List<Map<String, Object>> tools = (List<Map<String, Object>>) root.get("tools");
            rawArthasByName = new LinkedHashMap<>();
            for (Map<String, Object> t : tools) {
                rawArthasByName.put((String) t.get("name"), (Map<String, Object>) t.get("inputSchema"));
            }
        }
    }

    @Test
    void exposesExactly35Tools() {
        assertThat(registry.tools()).hasSize(35);
    }

    @Test
    void exposesExactly4GatewayOwnedToolsAllGatewayLocal() {
        List<ExposedTool> gateway = registry.tools().stream()
                .filter(t -> t.routingMode() == RoutingMode.GATEWAY_LOCAL).toList();
        assertThat(gateway).extracting(ExposedTool::name).containsExactlyInAnyOrder(
                "arthas-gateway.list-targets",
                "arthas-gateway.task-get",
                "arthas-gateway.task-list",
                "arthas-gateway.task-cancel");
        gateway.forEach(t -> assertThat(t.taskSupport())
                .as("网关自有工具不暴露 taskSupport").isNull());
    }

    @Test
    void all31ArthasToolsHaveTargetInjectedIntoPropertiesAndRequired() {
        List<ExposedTool> arthas = arthasTools();
        assertThat(arthas).hasSize(31);
        for (ExposedTool t : arthas) {
            Map<String, Object> props = propertiesOf(t);
            assertThat(props).as("%s 须含 target 参数", t.name()).containsKey("target");
            assertThat(props.get("target"))
                    .as("%s 的 target 须为 string 类型", t.name())
                    .isInstanceOfSatisfying(Map.class, m ->
                            assertThat(((Map<?, ?>) m).get("type")).isEqualTo("string"));
            assertThat(requiredOf(t)).as("%s 须把 target 列入 required", t.name()).contains("target");
            assertThat(additionalPropertiesOf(t))
                    .as("%s 须保持 additionalProperties=false", t.name()).isEqualTo(false);
        }
    }

    @Test
    void arthasInputSchemaMatchesSourceVerbatimExceptTarget() {
        // S-TL-3：剥离 target 后，exposed inputSchema 逐字等于 arthas 原始 schema
        for (ExposedTool t : arthasTools()) {
            Map<String, Object> original = rawArthasByName.get(t.name());
            assertThat(original).as("基准中须有 %s", t.name()).isNotNull();

            Map<String, Object> stripped = deepCopy(t.inputSchema());
            Map<String, Object> props = (Map<String, Object>) stripped.get("properties");
            props.remove("target");
            List<String> req = (List<String>) stripped.get("required");
            stripped.put("required", req.stream().filter(r -> !"target".equals(r)).collect(Collectors.toList()));

            assertThat(stripped).as("%s 剥离 target 后须逐字等于原始 schema", t.name()).isEqualTo(original);
        }
    }

    @Test
    void targetIsRequiredAndFirstInRequiredArray() {
        // server-contract §4 示例：required 首位为 target
        ExposedTool watch = registry.find("watch").orElseThrow();
        assertThat(requiredOf(watch)).first().isEqualTo("target");
    }

    @Test
    void taskSupportMatchesExpectations() {
        // S-TL-4：5 个 optional；dashboard 与其余 25 forbidden
        Set<String> optional = Set.of("monitor", "stack", "tt", "trace", "watch");
        for (ExposedTool t : arthasTools()) {
            TaskSupport expected = optional.contains(t.name()) ? TaskSupport.OPTIONAL : TaskSupport.FORBIDDEN;
            assertThat(t.taskSupport()).as("%s 的 taskSupport", t.name()).isEqualTo(expected);
        }
    }

    @Test
    void routingModeDistributionMatchesClassificationTable() {
        // 工具传输分类表：25 SYNC_DIRECT + 1 STREAM_AGGREGATE(dashboard) + 5 ASYNC_TASK + 4 GATEWAY_LOCAL
        Map<RoutingMode, Long> dist = registry.tools().stream()
                .collect(Collectors.groupingBy(ExposedTool::routingMode, Collectors.counting()));
        assertThat(dist).containsEntry(RoutingMode.SYNC_DIRECT, 25L)
                .containsEntry(RoutingMode.STREAM_AGGREGATE, 1L)
                .containsEntry(RoutingMode.ASYNC_TASK, 5L)
                .containsEntry(RoutingMode.GATEWAY_LOCAL, 4L);
    }

    @Test
    void dashboardIsStreamAggregateAndForbidden() {
        ExposedTool dashboard = registry.find("dashboard").orElseThrow();
        assertThat(dashboard.routingMode()).isEqualTo(RoutingMode.STREAM_AGGREGATE);
        assertThat(dashboard.taskSupport()).isEqualTo(TaskSupport.FORBIDDEN);
    }

    @Test
    void preservesSpecialArthasParamNamesWithoutNormalization() {
        // 三处不统一的 ClassLoader 命名须照实透传（MCP能力清单 §0.7）
        assertThat(propertiesOf(registry.find("getstatic").orElseThrow())).containsKey("className");
        assertThat(propertiesOf(registry.find("dump").orElseThrow())).containsKey("classLoaderHashcode");
        assertThat(propertiesOf(registry.find("sc").orElseThrow())).containsKey("classLoaderStr");
        // profiler 的 include/exclude 须为数组类型
        Map<String, Object> profilerProps = propertiesOf(registry.find("profiler").orElseThrow());
        assertThat(((Map<?, ?>) profilerProps.get("include")).get("type")).isEqualTo("array");
        assertThat(((Map<?, ?>) profilerProps.get("exclude")).get("type")).isEqualTo("array");
    }

    @Test
    void snapshotIsImmutable() {
        List<ExposedTool> tools = registry.tools();
        assertThatThrownBy(() -> tools.add(new ExposedTool("x", "d", Map.of(), null, RoutingMode.GATEWAY_LOCAL)))
                .isInstanceOf(UnsupportedOperationException.class);
        // 单个工具的 inputSchema 也不可被外部修改（防御深拷贝泄漏）
        ExposedTool watch = registry.find("watch").orElseThrow();
        assertThatThrownBy(() -> propertiesOf(watch).put("evil", Map.of()))
                .as("inputSchema 须不可变").isInstanceOfAny(UnsupportedOperationException.class);
    }

    @Test
    void findReturnsEmptyForUnknownTool() {
        assertThat(registry.find("does-not-exist")).isEmpty();
    }

    // ===== 辅助 =====

    private List<ExposedTool> arthasTools() {
        return registry.tools().stream()
                .filter(t -> t.routingMode() != RoutingMode.GATEWAY_LOCAL).toList();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> propertiesOf(ExposedTool t) {
        return (Map<String, Object>) t.inputSchema().get("properties");
    }

    @SuppressWarnings("unchecked")
    private List<String> requiredOf(ExposedTool t) {
        return (List<String>) t.inputSchema().get("required");
    }

    private Object additionalPropertiesOf(ExposedTool t) {
        return t.inputSchema().get("additionalProperties");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> deepCopy(Map<String, Object> src) {
        Map<String, Object> out = new LinkedHashMap<>();
        src.forEach((k, v) -> {
            if (v instanceof Map<?, ?> m) out.put(k, new LinkedHashMap<>((Map<String, Object>) m));
            else if (v instanceof List<?> l) out.put(k, new ArrayList<>((List<Object>) l));
            else out.put(k, v);
        });
        return out;
    }
}
