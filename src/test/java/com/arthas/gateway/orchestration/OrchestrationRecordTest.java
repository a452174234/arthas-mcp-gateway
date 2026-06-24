package com.arthas.gateway.orchestration;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T013 {@code OrchestrationRecord}/{@code OrchestrationRecordStore} 状态机与存储测试
 * （surefire 波次 A，纯逻辑，无 K8S）。
 *
 * <p>断言 data-model §8/§9：
 * <ul>
 *   <li>字段集合：logicalName/server/pod/namespace/mcpUrl/serviceRef/status/error/createdAt/completedAt。</li>
 *   <li>状态机：{@code ensuring}（非终态）→ {@code ready}/{@code reused}/{@code failed}（终态）。</li>
 *   <li>{@code createdAt} 为<b>传入</b>瞬时量（非进程内取时，与 001 {@code GatewayTask.createdAt} 一致，便于确定性测试）。</li>
 *   <li>{@code failed} 保留已暴露的 mcpUrl/serviceRef 副作用字段（§4.1：可清理副作用记录供运维追溯）。</li>
 *   <li>Store：按 logicalName 覆盖最新状态、get/list 语义。</li>
 * </ul>
 *
 * <p>TDD：先于实现编写（red：{@code OrchestrationRecord}/{@code OrchestrationRecordStore} 尚不存在）。
 */
class OrchestrationRecordTest {

    private static final Instant T0 = Instant.parse("2026-06-23T00:00:00Z");
    private static final Instant T1 = Instant.parse("2026-06-23T00:00:05Z");

    // ===== 状态机：ensuring（非终态） =====

    @Test
    void ensuringRecordCarriesPassedCreatedAtAndIsNonTerminal() {
        OrchestrationRecord r = OrchestrationRecord.ensuring("srv-pod", "srv", "pod", "default", T0);
        assertThat(r.logicalName()).isEqualTo("srv-pod");
        assertThat(r.server()).isEqualTo("srv");
        assertThat(r.pod()).isEqualTo("pod");
        assertThat(r.namespace()).isEqualTo("default");
        assertThat(r.status()).isEqualTo(OrchestrationRecord.Status.ENSURING);
        assertThat(r.createdAt()).as("createdAt 须为传入瞬时量（非进程取时）").isEqualTo(T0);
        assertThat(r.completedAt()).isNull();
        assertThat(r.mcpUrl()).isNull();
        assertThat(r.serviceRef()).isNull();
        assertThat(r.error()).isNull();
        assertThat(r.status().isTerminal()).as("ENSURING 非终态").isFalse();
    }

    // ===== ensuring → ready（新供给完成） =====

    @Test
    void transitionToReadyIsTerminalWithExposedUrlAndService() {
        OrchestrationRecord ready = OrchestrationRecord.ensuring("srv-pod", "srv", "pod", "default", T0)
                .ready("http://192.168.31.92:31234", "arthas-mcp-srv-pod/31234", T1);
        assertThat(ready.status()).isEqualTo(OrchestrationRecord.Status.READY);
        assertThat(ready.status().isTerminal()).isTrue();
        assertThat(ready.mcpUrl()).isEqualTo("http://192.168.31.92:31234");
        assertThat(ready.serviceRef()).isEqualTo("arthas-mcp-srv-pod/31234");
        assertThat(ready.completedAt()).isEqualTo(T1);
        assertThat(ready.error()).isNull();
        assertThat(ready.createdAt()).isEqualTo(T0); // 传入瞬时量贯穿转换
    }

    // ===== ensuring → reused（幂等复用命中） =====

    @Test
    void transitionToReusedIsTerminal() {
        OrchestrationRecord reused = OrchestrationRecord.ensuring("srv-pod", "srv", "pod", "default", T0)
                .reused("http://192.168.31.92:31234", "arthas-mcp-srv-pod/31234", T1);
        assertThat(reused.status()).isEqualTo(OrchestrationRecord.Status.REUSED);
        assertThat(reused.status().isTerminal()).isTrue();
        assertThat(reused.completedAt()).isEqualTo(T1);
    }

    // ===== ensuring → failed（终态；保留已暴露副作用供追溯；§4.1） =====

    @Test
    void transitionToFailedIsTerminalAndPreservesSideEffectsForTraceability() {
        OrchestrationRecord.Error err = new OrchestrationRecord.Error(
                "health-check", "unreachable", "arthas MCP 端口 30s 未就绪");
        // 假设暴露 Service 成功但健康检查失败：mcpUrl/serviceRef 应保留进 failed 记录（§4.1 可清理副作用）
        OrchestrationRecord failed = OrchestrationRecord.ensuring("srv-pod", "srv", "pod", "default", T0)
                .withExposed("http://192.168.31.92:31234", "arthas-mcp-srv-pod/31234")
                .failed(err, T1);
        assertThat(failed.status()).isEqualTo(OrchestrationRecord.Status.FAILED);
        assertThat(failed.status().isTerminal()).isTrue();
        assertThat(failed.error()).isEqualTo(err);
        assertThat(failed.error().phase()).isEqualTo("health-check");
        assertThat(failed.mcpUrl()).as("已暴露 mcpUrl 保留供运维追溯清理").isEqualTo("http://192.168.31.92:31234");
        assertThat(failed.serviceRef()).isEqualTo("arthas-mcp-srv-pod/31234");
        assertThat(failed.completedAt()).isEqualTo(T1);
    }

    // ===== Store：按 logicalName 覆盖、get/list =====

    @Test
    void storeOverwritesByLogicalNameAndSupportsGetList() {
        OrchestrationRecordStore store = new OrchestrationRecordStore();
        assertThat(store.get("x")).isEqualTo(Optional.empty());
        assertThat(store.list()).isEmpty();

        store.record(OrchestrationRecord.ensuring("a-pod", "a", "pod", "default", T0));
        store.record(OrchestrationRecord.ensuring("b-pod", "b", "pod", "default", T0));
        assertThat(store.list()).hasSize(2);

        // 同 logicalName 覆盖最新状态（ensuring → ready）
        store.record(OrchestrationRecord.ensuring("a-pod", "a", "pod", "default", T0)
                .ready("http://1.2.3.4:30000", "svc-a/30000", T1));
        assertThat(store.list()).as("覆盖而非新增").hasSize(2);
        Optional<OrchestrationRecord> a = store.get("a-pod");
        assertThat(a).isPresent();
        assertThat(a.get().status()).isEqualTo(OrchestrationRecord.Status.READY);
    }
}
