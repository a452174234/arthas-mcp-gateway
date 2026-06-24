package com.arthas.gateway.config;

import com.arthas.gateway.handler.McpErrorCodes;
import com.arthas.gateway.handler.ToolsCallRouter;
import com.arthas.gateway.orchestration.K8sToolHandlers;
import com.arthas.gateway.orchestration.K8sToolRegistry;
import com.arthas.gateway.tool.ExposedTool;
import com.arthas.gateway.tool.StaticToolRegistry;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.modelcontextprotocol.spec.McpError;
import org.springframework.ai.mcp.customizer.McpSyncServerCustomizer;
import org.springframework.beans.factory.ObjectProvider;
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
     * 38 个 MCP 工具规格 = 35 arthas/网关工具 + 3 K8S 编排工具（003 特性，契约 §1/§2/§3，R6）。
     *
     * <ul>
     *   <li>35 个静态工具：每个 {@link ExposedTool} 转 {@link Tool}（name/description/inputSchema 逐字），
     *       handler 委托 {@link ToolsCallRouter#route}（经 gateway-core 路由，传输无关）。</li>
     *   <li>3 个编排工具（{@link K8sToolRegistry#tools()}）：规格始终注册（{@code tools/list}=38 与 kubeconfig
     *       <b>无关</b>，回归守护 T029），handler 自带闭包、<b>不经 {@code ToolsCallRouter}</b>——直接调
     *       {@link K8sToolHandlers#handle}（gateway-core 路由零 K8S 感知，R6）。</li>
     * </ul>
     *
     * <p><b>编排 handler 条件装配</b>：{@link K8sToolHandlers} bean 仅在 kubeconfig 存在时装配（见
     * {@code K8sOrchestrationConfig} + {@code K8sEnabledCondition}）。此处经 {@link ObjectProvider} 懒解析：
     * bean 缺失（CI 无 k3s）→ 调编排工具返「K8S 编排未启用」明确错误（INVALID_PARAMS），而非启动期崩。
     *
     * <p>Spring AI starter 自动收集此 bean 注册到 MCP server，驱动 {@code tools/list} 返回。
     */
    @Bean
    List<SyncToolSpecification> mcpToolSpecifications(StaticToolRegistry registry, ToolsCallRouter router,
                                                      ObjectProvider<K8sToolHandlers> k8sHandlersProvider) {
        // 35 个静态工具 → 经 ToolsCallRouter 路由
        List<SyncToolSpecification> specs = new ArrayList<>(registry.tools().size() + K8sToolRegistry.tools().size());
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
        // 3 个编排工具 → handler 自带闭包、不经 ToolsCallRouter（ObjectProvider 懒解析 K8sToolHandlers）
        for (ExposedTool exposed : K8sToolRegistry.tools()) {
            Tool tool = McpSchema.Tool.builder()
                    .name(exposed.name())
                    .description(exposed.description())
                    .inputSchema(exposed.inputSchema())
                    .build();
            BiFunction<McpSyncServerExchange, CallToolRequest, CallToolResult> handler =
                    (exchange, request) -> {
                        K8sToolHandlers handlers = k8sHandlersProvider.getIfAvailable();
                        if (handlers == null) {
                            // kubeconfig 缺失（CI 无 k3s）→ 编排 bean 未装配，返明确错误
                            throw McpError.builder(McpErrorCodes.INVALID_PARAMS)
                                    .message("K8S 编排未启用：未配置可读的 arthas-gateway.k8s.kubeconfig"
                                            + "（工具 " + exposed.name() + " 需真实集群）")
                                    .build();
                        }
                        return handlers.handle(exposed, request);
                    };
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
