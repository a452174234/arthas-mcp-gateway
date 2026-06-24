package com.arthas.gateway.orchestration;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 供给记录内存态（data-model §8）。
 *
 * <p>按 {@code logicalName} 覆盖最新状态（一次 ensure 全程可能经历 ensuring→ready/reused/failed，
 * 仅终态对外有意义，但中间态亦写入便于近实时观测）。进程级、优雅关闭（MVP 不持久化）。
 *
 * <p>线程安全：内部 {@link ConcurrentHashMap}。供运维经结构化日志/未来 portal 查询一次 ensure 的来龙去脉。
 */
public class OrchestrationRecordStore {

    private final ConcurrentHashMap<String, OrchestrationRecord> byLogicalName = new ConcurrentHashMap<>();

    /** 写入/覆盖一条供给记录（按 logicalName 覆盖最新状态）。 */
    public void record(OrchestrationRecord r) {
        byLogicalName.put(r.logicalName(), r);
    }

    /** 取单个 logicalName 的最新记录（缺失返 empty）。 */
    public Optional<OrchestrationRecord> get(String logicalName) {
        return Optional.ofNullable(byLogicalName.get(logicalName));
    }

    /** 当前全部记录的不可变快照（无序保证）。 */
    public List<OrchestrationRecord> list() {
        return List.copyOf(byLogicalName.values());
    }

    /** 清空（测试/重置用）。 */
    public void clear() {
        byLogicalName.clear();
    }
}
