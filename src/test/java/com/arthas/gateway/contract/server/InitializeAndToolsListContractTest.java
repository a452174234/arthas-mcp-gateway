package com.arthas.gateway.contract.server;

import com.arthas.gateway.testfixtures.McpClientHarness;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 服务端契约测试：initialize 握手 + tools/list（contracts/server-contract.md §2-4、§7 断言点 S-INIT/S-TL）。
 *
 * <p>由官方 SDK client（{@link McpClientHarness}，走标准 MCP 协议、非裸 curl）驱动，断言网关作为
 * 标准 MCP 服务端对 Claude Code 的行为承诺。
 *
 * <p>本测试<b>不需要真实 arthas 后端</b>——initialize 与 tools/list 均为网关本地行为（协议骨架 + 静态注册表），
 * 故归 surefire（单元），不走 failsafe。tools/call 的真实后端路由由 US1 契约/集成测试覆盖。
 *
 * <p>TDD：先于服务端骨架编写，预期全红（工具数为 0 / 协议未装配）。
 *
 * @see McpClientHarness
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class InitializeAndToolsListContractTest {

    /** SDK 2.0.0 锁定的协议版本接受列表（S-INIT-1）。 */
    private static final Set<String> ACCEPTED_PROTOCOL_VERSIONS =
            Set.of("2024-11-05", "2025-03-26", "2025-06-18", "2025-11-25");

    /** 4 个网关自有工具名前缀（区分 arthas 工具与网关/K8S 工具）。 */
    private static final String GATEWAY_TOOL_PREFIX = "arthas-gateway.";

    /** 3 个 K8S 编排工具名前缀（003 特性：k8s.list-pods/k8s.list-services/k8s.ensure-arthas-mcp，无 target 参数）。 */
    private static final String K8S_TOOL_PREFIX = "k8s.";

    @LocalServerPort
    private int port;

    private String baseUrl() {
        return "http://localhost:" + port + "/mcp";
    }

    // ===== S-INIT =====

    /** S-INIT-1：协议版本在列表内原样回显（客户端请求其最高版本，服务端回显列表内值）。 */
    @Test
    void s_init_1_protocolVersionEchoedFromAcceptList() {
        try (McpClientHarness h = new McpClientHarness(baseUrl())) {
            McpSchema.InitializeResult init = h.initialize();
            assertThat(init.protocolVersion())
                    .as("回显的协议版本须在 SDK 锁定的接受列表内")
                    .isIn(ACCEPTED_PROTOCOL_VERSIONS);
        }
    }

    /** S-INIT-2：serverInfo 非空且名字为网关；capabilities.tools 存在；不声明 prompts/resources。 */
    @Test
    void s_init_2_serverInfoAndCapabilities() {
        try (McpClientHarness h = new McpClientHarness(baseUrl())) {
            McpSchema.InitializeResult init = h.initialize();
            assertThat(init.serverInfo()).as("serverInfo 非空").isNotNull();
            assertThat(init.serverInfo().name())
                    .as("serverInfo.name 为 arthas-mcp-gateway")
                    .isEqualTo("arthas-mcp-gateway");
            assertThat(init.serverInfo().version())
                    .as("serverInfo.version 非空")
                    .isNotBlank();

            McpSchema.ServerCapabilities caps = init.capabilities();
            assertThat(caps).as("capabilities 非空").isNotNull();
            assertThat(caps.tools()).as("capabilities.tools 存在").isNotNull();
            assertThat(caps.tools().listChanged())
                    .as("tools.listChanged == false（工具集静态，不广播变更）")
                    .isEqualTo(false);
            assertThat(caps.prompts()).as("不声明 prompts（arthas 恒空）").isNull();
            assertThat(caps.resources()).as("不声明 resources（arthas 恒空）").isNull();
        }
    }

    /** S-INIT-3：notifications/initialized 后网关不报错；客户端进入已初始化态。 */
    @Test
    void s_init_3_afterInitializedNoError() {
        try (McpClientHarness h = new McpClientHarness(baseUrl())) {
            assertThatCode(h::initialize)
                    .as("initialize 握手（含 initialized 通知）不抛异常")
                    .doesNotThrowAnyException();
            assertThat(h.client().isInitialized())
                    .as("握手后客户端进入已初始化态")
                    .isTrue();
        }
    }

    // ===== S-TL =====

    /** S-TL-1：tools/list 工具数 == 38（31 arthas + 4 网关自有 + 3 K8S 编排）。 */
    @Test
    void s_tl_1_toolsCountIs38() {
        try (McpClientHarness h = new McpClientHarness(baseUrl())) {
            h.initialize();
            List<Tool> tools = h.listTools().tools();
            assertThat(tools).as("工具总数 == 38（31 arthas + 4 网关自有 + 3 K8S 编排）").hasSize(38);
        }
    }

    /** S-TL-2：每个 arthas 工具 inputSchema.properties 含 target 且 ∈ required；additionalProperties==false。 */
    @Test
    void s_tl_2_everyArthasToolHasTargetInRequiredAndAdditionalPropertiesFalse() {
        try (McpClientHarness h = new McpClientHarness(baseUrl())) {
            h.initialize();
            List<Tool> arthasTools = arthasTools(h.listTools().tools());
            assertThat(arthasTools).as("31 个 arthas 工具").hasSize(31);

            for (Tool tool : arthasTools) {
                Map<String, Object> schema = tool.inputSchema();
                Map<String, Object> properties = asMap(schema.get("properties"));
                assertThat(properties)
                    .as("%s: properties 含 target", tool.name())
                    .containsKey("target");
                assertThat(requiredOf(schema))
                    .as("%s: required 含 target", tool.name())
                    .contains("target");
                assertThat(schema.get("additionalProperties"))
                    .as("%s: additionalProperties == false", tool.name())
                    .isEqualTo(false);
            }
        }
    }

    /**
     * S-TL-3：除 target 外，inputSchema 逐字等于 arthas 原始 schema（剥离 target 后与能力清单 baseline 比对）。
     *
     * <p>验证整条链路无损：arthas-tools.json → StaticToolRegistry（注入 target）→ SDK Tool → MCP 线 → 客户端反序列化。
     */
    @Test
    void s_tl_3_inputSchemaVerbatimExceptTarget() {
        Map<String, Map<String, Object>> baseline = loadArthasBaseline();
        try (McpClientHarness h = new McpClientHarness(baseUrl())) {
            h.initialize();
            for (Tool tool : arthasTools(h.listTools().tools())) {
                Map<String, Object> stripped = stripTarget(tool.inputSchema());
                assertThat(toSortedMap(stripped))
                    .as("%s: 剥离 target 后逐字等于 arthas baseline", tool.name())
                    .isEqualTo(toSortedMap(baseline.get(tool.name()).get("inputSchema")));
            }
        }
    }

    /**
     * S-TL-4（A1 调整）：taskSupport 仅内部路由用，<b>不</b>在协议发射——
     * 断言线上 38 个工具的 inputSchema 与 Tool 均<b>不含</b> taskSupport / execution 字段。
     *
     * <p>原 S-TL-4（execution.taskSupport 符合预期）的线上断言改在 StaticToolRegistryTest（注册表/路由层）覆盖；
     * 此处仅校验协议边界不泄漏内部字段（用户决定 A1，见 memory sdk2-vs-spec-divergences）。
     */
    @Test
    void s_tl_4_noExecutionTaskSupportLeakedOnWire() {
        try (McpClientHarness h = new McpClientHarness(baseUrl())) {
            h.initialize();
            for (Tool tool : h.listTools().tools()) {
                assertThat(tool.inputSchema())
                    .as("%s: inputSchema 不含 taskSupport（仅内部路由用）", tool.name())
                    .doesNotContainKey("taskSupport");
                assertThat(tool.inputSchema())
                    .as("%s: inputSchema 不含 execution（SDK 2.0.0 无此 draft 扩展）", tool.name())
                    .doesNotContainKey("execution");
            }
        }
    }

    /** S-TL-5：tools/list 的 nextCursor == null（38 工具静态，不分页）。 */
    @Test
    void s_tl_5_nextCursorIsNull() {
        try (McpClientHarness h = new McpClientHarness(baseUrl())) {
            h.initialize();
            assertThat(h.listTools().nextCursor())
                    .as("nextCursor == null（不分页）")
                    .isNull();
        }
    }

    // ===== 辅助 =====

    /**
     * 从线上工具中筛出 31 个 arthas 工具（排除 arthas-gateway.* 网关自有工具与 k8s.* K8S 编排工具）。
     *
     * <p>K8S 编排工具（003 特性）与网关自有工具均<b>不含</b> target 参数（非 arthas 诊断工具），
     * 须从 S-TL-2/S-TL-3 的 target 逐字比对中剔除，否则会误判 K8S 工具"缺 target"为违约。
     */
    private static List<Tool> arthasTools(List<Tool> all) {
        return all.stream()
                .filter(t -> !t.name().startsWith(GATEWAY_TOOL_PREFIX))
                .filter(t -> !t.name().startsWith(K8S_TOOL_PREFIX))
                .toList();
    }

    /** 从 inputSchema 取 required（List&lt;String&gt;）。 */
    @SuppressWarnings("unchecked")
    private static List<String> requiredOf(Map<String, Object> schema) {
        Object raw = schema.get("required");
        if (raw instanceof List<?> list) {
            List<String> out = new ArrayList<>();
            for (Object o : list) {
                out.add((String) o);
            }
            return out;
        }
        return List.of();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object raw) {
        return raw instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    /**
     * 剥离 target：从 properties 删 target 键、从 required 删 target 元素，其余逐字保留。
     */
    private static Map<String, Object> stripTarget(Map<String, Object> schema) {
        Map<String, Object> copy = deepMutable(schema);
        Map<String, Object> properties = asMap(copy.get("properties"));
        Map<String, Object> propsCopy = new LinkedHashMap<>(properties);
        propsCopy.remove("target");
        copy.put("properties", propsCopy);

        List<String> required = new ArrayList<>(requiredOf(copy));
        required.remove("target");
        copy.put("required", required);
        return copy;
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

    /** 转为按键名排序的 TreeMap，消除 Jackson/SDK 在 Map 键序上的非确定性，做逐字内容比对。 */
    private static Object toSortedMap(Object node) {
        if (node instanceof Map<?, ?> m) {
            Map<String, Object> sorted = new TreeMap<>();
            m.forEach((k, v) -> sorted.put((String) k, toSortedMap(v)));
            return sorted;
        }
        if (node instanceof List<?> l) {
            List<Object> out = new ArrayList<>();
            for (Object e : l) {
                out.add(toSortedMap(e));
            }
            return out;
        }
        return node;
    }

    /**
     * 独立加载 arthas-tools.json 作为逐字比对 baseline（不经过 StaticToolRegistry，避免循环依赖断言）。
     *
     * @return 工具名 → 该工具原始 spec（含 inputSchema，未注入 target）
     */
    private static Map<String, Map<String, Object>> loadArthasBaseline() {
        try (InputStream in = InitializeAndToolsListContractTest.class
                .getClassLoader().getResourceAsStream("arthas-tools.json")) {
            if (in == null) {
                throw new IllegalStateException("未找到 classpath 资源 arthas-tools.json");
            }
            Map<String, Object> root = new ObjectMapper()
                    .readValue(in, new TypeReference<Map<String, Object>>() {});
            List<Map<String, Object>> specs = asList(root.get("tools"));
            Map<String, Map<String, Object>> byName = new LinkedHashMap<>();
            for (Map<String, Object> spec : specs) {
                byName.put((String) spec.get("name"), spec);
            }
            return byName;
        } catch (Exception e) {
            throw new IllegalStateException("加载 arthas-tools.json baseline 失败", e);
        }
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
