package com.arthas.gateway.config;

import com.arthas.gateway.handler.ToolsCallRouter;
import com.arthas.gateway.tool.ExposedTool;
import com.arthas.gateway.tool.StaticToolRegistry;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import org.springframework.ai.mcp.customizer.McpSyncServerCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;

/**
 * 网关 MCP 服务端装配（SDK 2.0.0 / Spring AI 2.0.0 原生路径，见 memory sdk2-vs-spec-divergences）。
 *
 * <p>三件事：
 * <ol>
 *   <li>{@link StaticToolRegistry}——从 {@code arthas-tools.json} 构建不可变 35 工具快照（T013）。</li>
 *   <li>{@code List<SyncToolSpecification>}——35 工具注册为 SDK 工具规格，每个 handler 委托
 *       {@link ToolsCallRouter}（Phase 3 实现真实路由，当前占位返回结果，证明装配通路）。</li>
 *   <li>{@link McpSyncServerCustomizer}——锁定 capabilities 为<b>仅 tools（listChanged=false）</b>，
 *       不声明 prompts/resources/logging/completions（arthas 恒空，契约 S-INIT-2 / server-contract §3）。</li>
 * </ol>
 *
 * <p><b>与 spec 的偏差</b>（已记录 memory sdk2-vs-spec-divergences）：spec 计划手写
 * {@code InitializeHandler}/{@code ToolsListHandler}/{@code transport/} 装配类；SDK 原生路径下
 * initialize 协商、tools/list 返回、Streamable HTTP transport 均由 Spring AI starter + SDK 内置，
 * 本类仅提供工具规格与 capabilities 锁定——故 T015/T016/T017 不再产出独立 handler/transport 类。
 * 行为等价性由 {@code InitializeAndToolsListContractTest}（S-INIT/S-TL）契约测试守护。
 */
@Configuration
public class GatewayMcpServerConfig {

    /** 工具 schema 资源路径（单一事实源，T012）。 */
    private static final String TOOLS_RESOURCE = "arthas-tools.json";

    /**
     * 静态工具注册表：加载 31 个 arthas 工具 schema（注入 target）+ 4 个网关自有工具 = 35。
     *
     * <p>启动期一次性构建不可变快照，作为 {@code tools/list} 的单一填充源（宪法原则二：透明无损聚合）。
     */
    @Bean
    StaticToolRegistry staticToolRegistry() {
        return StaticToolRegistry.fromClasspath(TOOLS_RESOURCE);
    }

    /**
     * 35 个 MCP 工具规格：每个 {@link ExposedTool} 转 {@link Tool}（name/description/inputSchema 逐字），
     * handler 全部委托 {@link ToolsCallRouter#route}（传输无关，stdio/HTTP 两路共用同一 handler）。
     *
     * <p>Spring AI starter 自动收集此 bean 注册到 MCP server，驱动 {@code tools/list} 返回。
     */
    @Bean
    List<SyncToolSpecification> mcpToolSpecifications(StaticToolRegistry registry, ToolsCallRouter router) {
        List<SyncToolSpecification> specs = new ArrayList<>(registry.tools().size());
        for (ExposedTool exposed : registry.tools()) {
            Tool tool = McpSchema.Tool.builder()
                    .name(exposed.name())
                    .description(exposed.description())
                    .inputSchema(exposed.inputSchema())
                    .build();
            BiFunction<McpSyncServerExchange, CallToolRequest, CallToolResult> handler =
                    (exchange, request) -> router.route(exposed, request);
            specs.add(new SyncToolSpecification(tool, handler));
        }
        return specs;
    }

    /**
     * 锁定服务端 capabilities 为<b>仅 tools（listChanged=false）</b>，不声明 prompts/resources/logging/completions。
     *
     * <p>Spring AI starter 默认广播 prompts/resources/logging/completions（其 {@code Capabilities} 布尔默认
     * true，且 {@code tools.listChanged} 硬编码 true）——与本网关"仅暴露静态工具集"的契约（S-INIT-2 / §3）不符。
     * 故用官方 {@link McpSyncServerCustomizer} 扩展点精确覆盖。
     *
     * <p><b>覆盖时机</b>：starter 的 {@code mcpSyncServer} 先 {@code spec.capabilities(...)}（offset 557），
     * 再 {@code customizer.ifPresent(...)}（offset 590），最后 {@code spec.build()}——customizer 在后，可覆盖。
     *
     * <p><b>{@code @Primary} 的必要性</b>：starter 自带 {@code servletMcpSyncServerCustomizer}（非
     * {@code @ConditionalOnMissingBean}），与本 bean 并存成两个候选——若不标 {@code @Primary}，
     * {@code Optional<McpSyncServerCustomizer>} 因多候选解析为空，<b>两个都不执行</b>（含 starter 的）。
     * 标 {@code @Primary} 后本 bean 赢得注入；此处一并保留 starter servlet customizer 唯一职责
     * {@code immediateExecution(true)}（WebMVC 下工具 handler 在调用线程即时执行），避免覆盖导致行为退化。
     */
    @Bean
    @Primary
    McpSyncServerCustomizer gatewayCapabilitiesCustomizer() {
        return spec -> {
            // 保留 starter servlet customizer 的职责：WebMVC 下 handler 即时执行
            spec.immediateExecution(true);
            // 锁定 capabilities：仅 tools（listChanged=false，工具集静态），其余不声明
            spec.capabilities(
                    McpSchema.ServerCapabilities.builder()
                            .tools(false)   // ToolCapabilities[listChanged=false]
                            .build());
        };
    }
}
