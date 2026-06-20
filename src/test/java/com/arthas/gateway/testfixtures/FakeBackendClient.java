package com.arthas.gateway.testfixtures;

import com.arthas.gateway.backend.BackendClient;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 受控 {@link BackendClient} test double（002 整改 T002）。
 *
 * <p><b>用途</b>:为统一拦截层/熔断/initialize CAS/取消中断的<b>纯逻辑单测</b>触发<b>真实失败条件</b>——
 * 可配置 initialize 抛异常、callTool 抛指定异常(含 {@code McpError})/阻塞(支持中断)/返回固定标记结果。
 * <b>非 arthas 成功响应桩</b>:返回的标记结果({@code "fake-result"})仅为透传断言占位,不模拟 arthas 真实诊断;
 * arthas 真实响应保真度仍由既有 IT({@code ArthasMcpBackend} + {@code DemoBusinessApp})覆盖。
 * 与 {@code AsyncTaskExecutorTest} 的 {@code blockingWork}(时序占位、不产出成功结果)同一性质。
 */
public final class FakeBackendClient implements BackendClient {

    private final AtomicInteger initializeCount = new AtomicInteger(0);
    private volatile RuntimeException initializeThrow = null;
    private volatile RuntimeException callToolThrow = null;
    private volatile CountDownLatch callToolBlock = null;
    private volatile CallToolResult callToolResult =
            new CallToolResult(List.of(new TextContent("fake-result")), false, null, null);
    private volatile boolean initialized = false;

    /** initialize 被调用次数(测 CAS 原子性:并发 invoke 应恰好 1 次)。 */
    public int initializeCount() {
        return initializeCount.get();
    }

    public void setInitializeThrow(RuntimeException e) {
        this.initializeThrow = e;
    }

    public void setCallToolResult(CallToolResult result) {
        this.callToolResult = result;
    }

    public void setCallToolThrow(RuntimeException e) {
        this.callToolThrow = e;
    }

    /** 设置后 callTool 阻塞在该 latch(支持中断,模拟"后端在途被取消")。 */
    public void setCallToolBlock(CountDownLatch latch) {
        this.callToolBlock = latch;
    }

    @Override
    public void initialize() {
        initializeCount.incrementAndGet();
        if (initializeThrow != null) {
            throw initializeThrow;
        }
        initialized = true;
    }

    @Override
    public CallToolResult callTool(String name, Map<String, Object> arguments) {
        if (callToolBlock != null) {
            try {
                callToolBlock.await();
            } catch (InterruptedException ie) {
                // 恢复中断态(供 invoke 检测跳过 recordFailure),包装为运行时异常(接口不抛 checked)
                Thread.currentThread().interrupt();
                throw new RuntimeException("fake callTool interrupted", ie);
            }
        }
        if (callToolThrow != null) {
            throw callToolThrow;
        }
        return callToolResult;
    }

    @Override
    public boolean isInitialized() {
        return initialized;
    }

    @Override
    public void close() {
        // no-op
    }
}
