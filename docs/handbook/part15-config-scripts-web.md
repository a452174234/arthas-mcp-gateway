# Part 15 · 配置、脚本、前端、规格文档摘录

> 本附录摘录项目配置（pom/yml）、冒烟脚本、前端源码、4 特性 spec/research 等关键文档，作为完整工程参考。

## 构建与配置

### `pom.xml`

```
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <!--
        继承 Spring Boot 4.1.0（Spring AI 2.0.0 MCP server starter 硬依赖 spring-boot-starter-web:4.1.0）。
        提供：生命周期、配置外化、Actuator、依赖管理（Jackson 3 / JUnit5 / AssertJ）。
    -->
    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>4.1.0</version>
        <relativePath/>
    </parent>

    <groupId>com.arthas.gateway</groupId>
    <artifactId>arthas-mcp-gateway</artifactId>
    <version>0.1.0-SNAPSHOT</version>
    <name>arthas-mcp-gateway</name>
    <description>arthas MCP 网关：聚合多个目标 JVM 的 arthas 诊断能力，向 Claude Code 等客户端统一暴露</description>

    <properties>
        <!-- 宪法「技术与传输约束」：Java LTS，构建中锁定为 21（虚拟线程利于后台异步任务） -->
        <java.version>21</java.version>
        <maven.compiler.release>21</maven.compiler.release>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
        <!-- 官方 MCP Java SDK GA；与 arthas 后端互通（arthas 4.3.0 锁 0.17.0，互通由 T009 真实握手实测裁决） -->
        <mcp.version>2.0.0</mcp.version>
        <!-- Spring AI 2.0.0 GA：提供 MCP server 自动装配（WebMVC 传输 + 工具注册） -->
        <spring-ai.version>2.0.0</spring-ai.version>
        <!-- 004 前端构建可跳过（开发期快速验证后端：-DskipFrontend=true）；默认 false 跑前端（CI/verify 出含前端 JAR，FR-011） -->
        <skipFrontend>false</skipFrontend>
    </properties>

    <!-- 双 BOM 锁版本：spring-ai-bom 管 spring-ai-* 与 org.springframework.ai 传输模块；mcp-bom 管 io.modelcontextprotocol.sdk 核心 -->
    <dependencyManagement>
        <dependencies>
            <dependency>
                <groupId>org.springframework.ai</groupId>
                <artifactId>spring-ai-bom</artifactId>
                <version>${spring-ai.version}</version>
                <type>pom</type>
                <scope>import</scope>
            </dependency>
            <dependency>
                <groupId>io.modelcontextprotocol.sdk</groupId>
                <artifactId>mcp-bom</artifactId>
                <version>${mcp.version}</version>
                <type>pom</type>
                <scope>import</scope>
            </dependency>
        </dependencies>
    </dependencyManagement>

    <dependencies>
        <!--
            服务端（面向 Claude Code）：Spring AI MCP server starter。
            自动装配 WebMVC Streamable HTTP 传输 + MCP server 生命周期 + 工具注册为 Spring bean。
            传递带入 mcp-spring-webmvc(含 mcp-core) + spring-ai-mcp + spring-boot-starter-web。
            2.0.0 jar 实测：传输类 HttpServletStreamableServerTransportProvider / StdioServerTransportProvider 均在 mcp-core。
        -->
        <dependency>
            <groupId>org.springframework.ai</groupId>
            <artifactId>spring-ai-starter-mcp-server-webmvc</artifactId>
        </dependency>

        <!--
            客户端（连 arthas 后端）：Spring AI MCP client starter。
            传递带入 spring-ai-mcp → io.modelcontextprotocol.sdk:mcp（含 HttpClientStreamableHttpTransport / McpClient / McpSyncClient）。
            注意：该 starter 的自动装配按静态属性创建单例客户端，不符合网关"动态多后端 + per-target 熔断/限流/独立连接池/认证头"
            （spec 原则三）的需求——BackendClient（T024）将排除其自动装配、用其传输类手搓 per-target 客户端。
        -->
        <dependency>
            <groupId>org.springframework.ai</groupId>
            <artifactId>spring-ai-starter-mcp-client</artifactId>
        </dependency>

        <!-- 宪法原则五：可观测性（健康检查 / metrics），Actuator 端点不涉 MCP 调用 -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-actuator</artifactId>
        </dependency>

        <!--
            K8S 编排客户端（003 特性，research.md R2）：io.fabric8:kubernetes-client。
            list pods/services、exec 进 pod、create NodePort Service 全走 fluent Java API，
            不 shell-out kubectl 二进制（宪法原则六「K8S 仅辅助、核心逻辑 Java」）。
            显式声明 7.6.1（2026-03 稳定版，Java 21 兼容），保证可复现构建（不进 BOM，需手动锁版本）。
            仅 orchestration 包依赖，gateway-core 包零 K8S 依赖（包级边界，ArchUnit 守护，T030）。
        -->
        <dependency>
            <groupId>io.fabric8</groupId>
            <artifactId>kubernetes-client</artifactId>
            <version>7.6.1</version>
        </dependency>
        <!--
            fabric8 文件上传运行时依赖（003 特性，T028 实测裁决）：
            ArthasProvisioner.installArthas 经 `.file().upload()` 上传 arthas-boot.jar 进 pod，
            fabric8 PodUpload 用 commons-compress 的 TarArchiveOutputStream/TarArchiveEntry 打 tar 流
            （javap 字节码铁证）。fabric8 将 commons-compress 声明为 optional（kubernetes-client-project
            pom：commons-compress.version=1.28.0）→ 不传递，spring-boot uber jar 重打包会缺失 →
            运行时 NoClassDefFoundError，K8S 纳管 ensure 失败于 install_arthas。显式声明（与 fabric8 7.6.1
            同版本）使其进 BOOT-INF/lib。仅 orchestration 包间接使用（gateway-core 零依赖，T030 守护）。
        -->
        <dependency>
            <groupId>org.apache.commons</groupId>
            <artifactId>commons-compress</artifactId>
            <version>1.28.0</version>
        </dependency>

        <!-- 配置外化元数据（@ConfigurationProperties） -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-configuration-processor</artifactId>
            <optional>true</optional>
        </dependency>

        <!-- ===== 测试（真实环境、零桩；JUnit5 + AssertJ 由 spring-boot-starter-test 管理） ===== -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
        <!-- 官方 MCP 测试脚手架（契约/一致性辅助） -->
        <dependency>
            <groupId>io.modelcontextprotocol.sdk</groupId>
            <artifactId>mcp-test</artifactId>
            <scope>test</scope>
        </dependency>
        <!-- ArchUnit 包级边界守护（T030：gateway-core 诊断核心零 K8S 依赖，research.md R1/R6；
             test-scope 轻量依赖，仅静态分析主代码包结构，不涉运行时 / 不引新构建工具链） -->
        <dependency>
            <groupId>com.tngtech.archunit</groupId>
            <artifactId>archunit-junit5</artifactId>
            <version>1.3.0</version>
            <scope>test</scope>
        </dependency>
        <!--
            fabric8 官方 K8S API mock server（005 US1 T009：NodePortExposer 决策逻辑单测）。
            NodePortExposer 直接持 KubernetesClient 调 fluent 链（services/pods/nodes），与 fabric8
            紧耦合——Mockito RETURNS_DEEP_STUBS 对 fabric8 复杂泛型 fluent 链不友好（inNamespace 中间返回 null）。
            fabric8 官方测试方式即 KubernetesMockServer（真实 HTTP API 模拟：labelSelector 查询、PATCH、POST）。
            与既有 kubernetes-client 同源同版本 7.6.1（fabric8 官方 SDK，test-scope，不引新工具链；宪法「优先官方 SDK」）。
            真实 k3s 端到端仍由 NodePortExposerContractIT（T010）覆盖。
        -->
        <dependency>
            <groupId>io.fabric8</groupId>
            <artifactId>kubernetes-server-mock</artifactId>
            <version>7.6.1</version>
            <scope>test</scope>
        </dependency>

        <!--
            arthas-boot.jar 不作为本工程依赖（用户约束 2026-06-20：本工程不依赖 arthas——
            既不入 pom 依赖，也不依赖 reference/arthas 源码构建）。
            arthas-boot.jar 作为可执行 fat jar（静态工具文件）置于 tools/arthas-boot.jar，
            T009 ArthasMcpBackend 经 `java -jar` 使用（详见 docs/superpowers/specs/2026-06-20-arthas-test-fixture-design.md §5）。
        -->
    </dependencies>

    <build>
        <plugins>
            <!-- 编译：锁定 release 21，保留参数名 -->
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-compiler-plugin</artifactId>
                <configuration>
                    <release>21</release>
                    <parameters>true</parameters>
                </configuration>
            </plugin>

            <!-- 质量门禁：强制 Java 21 + Maven 版本（宪法「构建中锁定」+「CI 复现本地构建」） -->
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-enforcer-plugin</artifactId>
                <executions>
                    <execution>
                        <id>enforce-build-prerequisites</id>
                        <goals>
                            <goal>enforce</goal>
                        </goals>
                        <configuration>
                            <rules>
                                <requireJavaVersion>
                                    <version>[21,22)</version>
                                </requireJavaVersion>
                                <requireMavenVersion>
                                    <version>[3.9.0,)</version>
                                </requireMavenVersion>
                            </rules>
                        </configuration>
                    </execution>
                </executions>
            </plugin>

            <!-- 单元测试：src/test 下 *Test.java（纯逻辑/状态机/解析单测，非真实后端） -->
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-surefire-plugin</artifactId>
                <configuration>
                    <excludes>
                        <exclude>**/*IT.java</exclude>
                    </excludes>
                </configuration>
            </plugin>

            <!-- 集成测试：*IT.java 绑定到 integration-test/verify 阶段（真实 arthas + 真实业务服务） -->
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-failsafe-plugin</artifactId>
                <executions>
                    <execution>
                        <goals>
                            <goal>integration-test</goal>
                            <goal>verify</goal>
                        </goals>
                    </execution>
                </executions>
            </plugin>

            <!-- Spring Boot 打包（可执行 jar，支持 stdio / HTTP 双传输运行） -->
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
            </plugin>

            <!--
                004 前端构建集成（research.md R11）：frontend-maven-plugin 在 Maven 内跑 npm install + build。
                - workingDirectory=web/（package.json 所在）
                - 下载指定版本 node（CI 无需预装、复现本地构建，宪法「CI 复现」）
                - vite build outDir=target/classes/static（web/vite.config.ts）→ Spring Boot 打包进 JAR（FR-011）
                - generate-resources 阶段（compile 前）；test 阶段跑 vitest（前端组件测试，FR-013）
                前端=展示层（宪法原则六 R13），核心逻辑 Java 后端。
            -->
            <plugin>
                <groupId>com.github.eirslett</groupId>
                <artifactId>frontend-maven-plugin</artifactId>
                <version>1.15.1</version>
                <configuration>
                    <workingDirectory>${project.basedir}/web</workingDirectory>
                    <installDirectory>${project.basedir}/web</installDirectory>
                    <nodeVersion>v22.22.0</nodeVersion>
                    <skip>${skipFrontend}</skip>
                </configuration>
                <executions>
                    <execution>
                        <id>install-node-and-npm</id>
                        <goals><goal>install-node-and-npm</goal></goals>
                        <phase>generate-resources</phase>
                    </execution>
                    <execution>
                        <id>npm-install</id>
                        <goals><goal>npm</goal></goals>
                        <phase>generate-resources</phase>
                        <configuration><arguments>install</arguments></configuration>
                    </execution>
                    <execution>
                        <id>npm-build</id>
                        <goals><goal>npm</goal></goals>
                        <phase>generate-resources</phase>
                        <configuration><arguments>run build</arguments></configuration>
                    </execution>
                    <execution>
                        <id>npm-test</id>
                        <goals><goal>npm</goal></goals>
                        <phase>test</phase>
                        <configuration><arguments>run test</arguments></configuration>
                    </execution>
                </executions>
            </plugin>
        </plugins>
    </build>

</project>
```

### `src/main/resources/application.yml`

```
# arthas MCP 网关配置
# 实体见 data-model.md，线行为见 contracts/，决策见 research.md
# ⚠ SDK 2.0.0 / Spring AI 2.0.0 偏离 spec 的处理见 memory sdk2-vs-spec-divergences：
#   传输由 Spring AI starter 自动装配（非自定义 arthas-gateway.transports.*）；
#   MVP 仅 Streamable HTTP（stdio 互斥，延后）。

spring:
  application:
    name: arthas-mcp-gateway
  main:
    banner-mode: console

  # === MCP 服务端（面向 Claude Code），由 Spring AI starter 自动装配 ===
  ai:
    mcp:
      server:
        enabled: true
        name: arthas-mcp-gateway          # serverInfo.name（S-INIT-2）
        version: 0.1.0                    # serverInfo.version
        type: SYNC                        # 同步 server
        protocol: STREAMABLE              # Streamable HTTP（非 SSE/STATELESS）
        stdio: false                      # MVP 不开 stdio（与 HTTP 互斥）
        # capabilities 不在此配置——由 GatewayMcpServerConfig#gatewayCapabilitiesCustomizer 锁定
        # 为「仅 tools（listChanged=false）」，不声明 prompts/resources/logging/completions（S-INIT-2 / §3）。
        # starter 默认会广播全部能力且 listChanged=true，customizer 覆盖之（customizer 在 spec.capabilities 后执行）。
        streamable-http:
          mcp-endpoint: /mcp              # MCP 端点路径（默认 /mcp）

# === Servlet 容器（WebMVC Streamable HTTP 跑在嵌入式 Tomcat） ===
server:
  port: 8761
  address: 0.0.0.0

arthas-gateway:
  # 后端映射表文件路径（BackendConfigLoader 解析、WatchService 热重载，免重启）
  backends-file: config/backends.yaml
  task:
    # 后台阻塞等后端路①的兜底超时（> 后端 10min 上限）
    backend-timeout: 11m
    # 已完成任务可查询保留时长
    result-ttl: 1h
  # K8S 编排子段（003 特性：k8s.list-* / k8s.ensure-arthas-mcp 工具连测试集群 + 供给参数）
  # 决策见 specs/003-k8s-arthas-mcp-launch/research.md R2/R3
  k8s:
    # kubeconfig：root-on-node 派生的 admin 凭证（test-env/k8s/setup.sh 导出，gitignore）
    kubeconfig: test-env/k8s/kubeconfig/k3s-admin.yaml
    # context：null/缺省取 kubeconfig current-context
    # context:
    namespace: default
    # NodePort 自动分配范围（K8S 默认 30000–32767）
    node-port-range: 30000-32767
    # ensure 全流程超时（注入 + 暴露 + 健康检查 + 注册），须 > arthas attach + 健康轮询
    ensure-timeout: 5m
  # K8S Host 列表（005 特性）：远端 Linux K8S 集群入口声明（BackendConfig K8S 模式 k8s-host 引用 name）。
  # 每 host 独立 kubeconfig + namespace；重启生效（热重载后置）。MVP 默认空（K8S 模式 backend 未配置时用 k8s.kubeconfig 单集群）。
  # 示例：
  # k8s-hosts:
  #   - name: debian-prod
  #     kubeconfig: test-env/k8s/kubeconfig/k3s-admin.yaml
  #     namespace: default
  k8s-hosts: []
  # portal 管理面能力开关（004 特性，research.md R9：@ConditionalOnProperty 按需启用，默认开）
  admin:
    crud:
      enabled: true      # 后端配置 CRUD（/admin/backends）；false 则端点 404、前端降级（INV-SWITCH-1）
    export:
      enabled: true      # 异步任务结果导出（/admin/tasks/{id}/export）；false 则端点 404（INV-SWITCH-2）

# 宪法原则五：Actuator 暴露网关健康与状态（运维无需读源码；唯一允许直连端点，不涉 MCP 调用）
management:
  endpoints:
    web:
      exposure:
        include: health,info
  endpoint:
    health:
      show-details: always

logging:
  level:
    com.arthas.gateway: INFO
```

### `config/backends.yaml`

```
# 后端映射表（示例模板）
# 实体定义见 data-model.md §2（BackendConfig）；解析与校验规则见 data-model.md §11
# 逻辑名 name 即 tools/call 的 target 参数取值；配置改动由 WatchService 热重载（SC-002）
#
# 注意：此处为示例目标（本地回环端口，示例用）。真实测试由 Phase 2 测试夹具在动态端口拉起真实 arthas。
# §9 实测（T009 首测 GREEN）：arthas 4.3.0 MCP 端点为根 URL（无 /mcp 后缀）。
#
# 003 特性（动态纳管）：可选字段 source 标记后端来源。
#   - source: STATIC   源自此 YAML 种子，受热重载增删（缺省值，不写即 STATIC，向后兼容）
#   - source: DYNAMIC  源自程序化注册（k8s.ensure-arthas-mcp 触发），不写回本文件、不受 YAML 热重载增删
# 动态注册的 target 与静态种子共同经 RegistryComposer 合并进 effective 注册表（见
# specs/003-k8s-arthas-mcp-launch/contracts/dynamic-registration-invariants.md）。
# 下列示例种子均为 STATIC，无需显式标注（缺省即 STATIC，零改动可用）。

version: 1

backends:
  - name: order-service
    url: http://127.0.0.1:8563    # 根 URL（§9：arthas MCP 端点无 /mcp 后缀）
    protocol: STREAMABLE          # STREAMABLE | STATELESS
    auth:
      mode: NONE                  # NONE | BEARER | BASIC
    connectTimeoutMs: 5000        # TCP + initialize 握手超时
    callTimeoutMs: 30000          # 单次同步 tools/call 超时（对齐 SC-003）
    maxConcurrentTasks: 5         # task 并发上限（后端硬约束）

  - name: payment
    url: http://127.0.0.1:8564/mcp
    protocol: STREAMABLE
    auth:
      mode: BEARER
      # token == 后端配置 password（见 reference/arthas-docs/03-MCP/后端接入契约.md §2.3）
      # 占位用环境变量，避免明文入库
      token: ${PAYMENT_TOKEN:change-me}
    connectTimeoutMs: 5000
    callTimeoutMs: 30000
    maxConcurrentTasks: 5
```

### `web/package.json`

```
{
  "name": "arthas-portal-web",
  "version": "0.1.0",
  "private": true,
  "type": "module",
  "description": "arthas portal 后端管理平台前端（004：Vue 3 SPA，内嵌网关单 JAR）",
  "scripts": {
    "dev": "vite",
    "build": "vue-tsc --noEmit && vite build",
    "preview": "vite preview",
    "test": "vitest run",
    "test:watch": "vitest"
  },
  "dependencies": {
    "vue": "^3.5.13",
    "vue-router": "^4.5.0"
  },
  "devDependencies": {
    "@vitejs/plugin-vue": "^5.2.1",
    "@vue/test-utils": "^2.4.6",
    "jsdom": "^25.0.1",
    "typescript": "^5.7.2",
    "vite": "^5.4.11",
    "vitest": "^2.1.8",
    "vue-tsc": "^2.1.10"
  }
}
```

### `web/vite.config.ts`

```
/// <reference types="vitest" />
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// 004 portal 前端构建配置（research.md R10/R11/R12）：
//  - build.outDir → ../target/classes/static：产物落 Maven 输出，Spring Boot 打包进 JAR
//    （同源服务、单 JAR；target/ 已 gitignore，不入库）
//  - dev server proxy /admin + /actuator → :8761：开发期前后端协作（HMR）
//  - test: jsdom 环境（Vitest 组件测试，@vue/test-utils）
export default defineConfig({
  plugins: [vue()],
  build: {
    outDir: '../target/classes/static',
    emptyOutDir: true,
  },
  server: {
    port: 5173,
    proxy: {
      '/admin': 'http://localhost:8761',
      '/actuator': 'http://localhost:8761',
    },
  },
  test: {
    environment: 'jsdom',
    globals: true,
  },
})
```

### `web/tsconfig.json`

```
{
  "compilerOptions": {
    "target": "ES2022",
    "useDefineForClassFields": true,
    "module": "ESNext",
    "moduleResolution": "bundler",
    "strict": true,
    "jsx": "preserve",
    "resolveJsonModule": true,
    "isolatedModules": true,
    "esModuleInterop": true,
    "skipLibCheck": true,
    "noEmit": true,
    "lib": ["ES2022", "DOM", "DOM.Iterable"],
    "types": ["vitest/globals"],
    "baseUrl": ".",
    "paths": { "@/*": ["src/*"] }
  },
  "include": ["src/**/*.ts", "src/**/*.vue", "env.d.ts"],
  "references": [{ "path": "./tsconfig.node.json" }]
}
```

## 冒烟脚本与启动器（smoke/）

### `smoke/gateway-start.sh`

```
#!/usr/bin/env bash
# =============================================================================
# 常驻启动 arthas MCP 网关 + 双真实后端（detached：nohup + &，进程脱离调用会话）
# 幂等：先清残留再起。用法： bash smoke/gateway-start.sh
# 停止： bash smoke/gateway-stop.sh
# =============================================================================
set -u
J21="${J21:-/c/Program Files/Java/jdk-21}"
ROOT="D:/vibe_Coding/arthas-gateway"
cd "$ROOT" || { echo "[start] cd 失败"; exit 1; }

echo "[start] 清理残留进程（若有）..."
bash smoke/gateway-stop.sh >/dev/null 2>&1 || true
sleep 1

echo "[start] ① 启动双真实后端（SmokeDemoLauncher）..."
nohup "$J21/bin/java" -Dbasedir="$ROOT" \
  -cp "target/test-classes;target/smoke-classes" \
  com.arthas.gateway.smoke.SmokeDemoLauncher > target/smoke-launcher.log 2>&1 &
# 非交互 bash 退出不发 SIGHUP；进程被重父到 session，脱离会话托管（常驻）

echo "[start] ② 等待后端 READY（最多 90s，含首跑 arthas 4.3.0 下载）..."
ok=0
for i in $(seq 1 90); do
  if grep -q "SMOKE_DEMO_READY" target/smoke-launcher.log 2>/dev/null; then ok=1; break; fi
  sleep 1
done
if [ "$ok" != "1" ]; then
  echo "[start] ✗ 后端未就绪，见 target/smoke-launcher.log"; tail -20 target/smoke-launcher.log; exit 1
fi
grep -E "baseUrl|appPort|mcpPort|SMOKE_DEMO_READY" target/smoke-launcher.log

echo "[start] ③ 启动网关 :8761（指向 backends-runtime.yaml）..."
nohup "$J21/bin/java" -jar target/arthas-mcp-gateway-0.1.0-SNAPSHOT.jar \
  --arthas-gateway.backends-file=config/backends-runtime.yaml > target/smoke-gateway.log 2>&1 &

echo "[start] ④ 等待网关 :8761 就绪（最多 40s）..."
ok=0
for i in $(seq 1 40); do
  if curl -sf --max-time 3 http://127.0.0.1:8761/actuator/health >/dev/null 2>&1; then ok=1; break; fi
  sleep 1
done
if [ "$ok" != "1" ]; then
  echo "[start] ✗ 网关未就绪，见 target/smoke-gateway.log"; tail -30 target/smoke-gateway.log; exit 1
fi

echo "[start] ⑤ 健康检查："
curl -s --max-time 5 http://127.0.0.1:8761/actuator/health | tr ',' '\n' \
  | grep -E "summary|\"healthy\"|state|breaker" | head
echo ""
echo "[start] ✓ 常驻就绪。MCP 端点 http://127.0.0.1:8761/mcp"
echo "       停止： bash smoke/gateway-stop.sh"
```

### `smoke/gateway-stop.sh`

```
#!/usr/bin/env bash
# =============================================================================
# 停止常驻 arthas MCP 网关 + 双后端（按命令行匹配杀全部相关 java 进程）
# 覆盖：网关 arthas-mcp-gateway.jar / GatewayApplication / 启动器 SmokeDemoLauncher /
#       业务服务 DemoBusinessApp / arthas-boot.jar
# 注：网关以 `java -jar arthas-mcp-gateway-*.jar` 启动时命令行不含主类名 GatewayApplication，
#     仅含 jar 路径，故须显式匹配 arthas-mcp-gateway（否则 java -jar 启动的网关杀不掉）。
# 用法： bash smoke/gateway-stop.sh
# =============================================================================
powershell -NoProfile -Command \
  "Get-CimInstance Win32_Process -Filter \"Name='java.exe'\" | Where-Object { \$_.CommandLine -match 'arthas-mcp-gateway|GatewayApplication|SmokeDemoLauncher|DemoBusinessApp|arthas-boot' } | ForEach-Object { Write-Output ('kill PID=' + \$_.ProcessId); Stop-Process -Id \$_.ProcessId -Force }" \
  2>/dev/null || true
echo "[stop] 已清理相关 java 进程（若有）。"
```

### `smoke/r4-bind-test.sh`

```
#!/usr/bin/env bash
# R4 实证（一次性验证工具，非生产代码 / 非 spec 任务）：
# arthas --target-ip 直接决定 MCP HTTP 监听 socket 的绑定地址。
# A/B 对照：同一真实 DemoBusinessApp JVM，分别以 0.0.0.0 / 127.0.0.1 attach，
# netstat 断言监听 socket 分别为 0.0.0.0:<port>（wildcard，NodePort 可达）与 127.0.0.1:<port>（loopback）。
#
# 前置：先 `mvn test-compile`（产出 target/test-classes/DemoBusinessApp）；arthas 4.3.0 运行时已缓存于 ~/.arthas/lib/4.3.0。
# 用法：bash smoke/r4-bind-test.sh
set -u
cd "$(dirname "$0")/.."   # 切到工程根

APP_A_PORT=39181
MCP_A_PORT=39182   # --target-ip 0.0.0.0
APP_B_PORT=39183
MCP_B_PORT=39184   # --target-ip 127.0.0.1

KILL_PIDS=()
cleanup() {
  echo "---CLEANUP---"
  for p in "${KILL_PIDS[@]:-}"; do
    [ -n "$p" ] && taskkill //F //PID "$p" >/dev/null 2>&1 || kill -9 "$p" 2>/dev/null || true
  done
}
trap cleanup EXIT

# 端口空闲检查
for p in $APP_A_PORT $MCP_A_PORT $APP_B_PORT $MCP_B_PORT; do
  if netstat -ano | grep -q ":$p .*LISTENING"; then echo "PORT_BUSY $p（请换端口）"; exit 2; fi
done

# 用 bash /dev/tcp 轮询端口监听
wait_listen() {  # $1=port
  local port=$1 i
  for i in $(seq 1 100); do
    if (exec 3<>/dev/tcp/127.0.0.1/$port) 2>/dev/null; then exec 3>&- 3<&-; return 0; fi
    sleep 0.5
  done
  return 1
}

# jps 取 DemoBusinessApp 的 OS PID（规避 Git Bash $! 与 Windows PID 不一致）
app_pid_by_jps() {
  jps -l 2>/dev/null | awk '/DemoBusinessApp/{print $1; exit}'
}

run_phase() {  # $1=label $2=appPort $3=mcpPort $4=targetIp
  local label=$1 appPort=$2 mcpPort=$3 targetIp=$4
  echo ""
  echo "===== PHASE $label : target-ip=$targetIp  app=$appPort  mcp=$mcpPort ====="
  local log="target/r4-app-$label.log"
  java -cp target/test-classes -Ddemo.slowMs=0 \
    com.arthas.gateway.testfixtures.DemoBusinessApp "$appPort" > "$log" 2>&1 &
  local bashpid=$!
  KILL_PIDS+=("$bashpid")
  if ! wait_listen "$appPort"; then echo "[$label] APP 未就绪，见 $log"; return 1; fi
  local pid
  pid=$(app_pid_by_jps)
  if [ -z "$pid" ]; then echo "[$label] jps 未找到 DemoBusinessApp"; return 1; fi
  echo "[$label] DemoBusinessApp OS PID=$pid"
  local alog="target/r4-attach-$label.log"
  # attach 进程注入 agent 后 exit 0；MCP HTTP 常驻目标 JVM
  if ! java -jar tools/arthas-boot.jar "$pid" \
        --attach-only --target-ip "$targetIp" \
        --telnet-port 0 --http-port "$mcpPort" \
        --use-version 4.3.0 > "$alog" 2>&1; then
    echo "[$label] arthas attach 失败（exit!=0），见 $alog"; return 1
  fi
  if ! wait_listen "$mcpPort"; then echo "[$label] MCP 端口未监听，见 $alog"; return 1; fi
  echo "[$label] MCP 已监听，netstat 本地地址："
  netstat -ano | grep ":$mcpPort " | grep LISTENING | awk '{print "      " $2 "  state=" $4 "  pid=" $5}'
  echo "[$label] 期望绑定地址前缀: $targetIp:$mcpPort"
}

PASS=0
run_phase A "$APP_A_PORT" "$MCP_A_PORT" "0.0.0.0"   && PASS=$((PASS+1))
run_phase B "$APP_B_PORT" "$MCP_B_PORT" "127.0.0.1" && PASS=$((PASS+1))

echo ""
echo "===== 结论 ====="
echo "两阶段均完成：$PASS/2"
echo "PHASE A 绑定地址应形如 0.0.0.0:$MCP_A_PORT（wildcard → NodePort 可达）"
echo "PHASE B 绑定地址应形如 127.0.0.1:$MCP_B_PORT（loopback → NodePort 不可达）"
exit 0
```

### `smoke/SmokeDemoLauncher.java`

```
package com.arthas.gateway.smoke;

import com.arthas.gateway.testfixtures.ArthasMcpBackend;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * 冒烟演示启动器（一次性端到端验证工具，非生产代码 / 非 spec 任务）。
 *
 * <p>复用 {@link ArthasMcpBackend} 夹具拉起 2 个真实 arthas MCP 后端（order-service / payment），
 * 每个后端各带一个真实业务 JVM（{@code com.arthas.gateway.testfixtures.DemoBusinessApp}，hotMethod 持续触发）。
 * 随后写出运行时后端映射表 {@code config/backends-runtime.yaml}（NONE 认证 + 动态端口），并常驻
 * （供网关与 Claude Code 连接）。进程被 kill / Ctrl+C 时经 shutdown hook 关闭全部子进程。
 *
 * <p>用法（须显式 JDK 21 + 设 basedir）：
 * <pre>{@code
 * java -Dbasedir=<工程根绝对路径> -cp target/test-classes:target/smoke-classes \
 *      com.arthas.gateway.smoke.SmokeDemoLauncher
 * }</pre>
 */
public final class SmokeDemoLauncher {

    public static void main(String[] args) throws Exception {
        if (System.getProperty("basedir") == null) {
            System.setProperty("basedir", Path.of(".").toAbsolutePath().normalize().toString());
        }
        Path basedir = Path.of(System.getProperty("basedir")).toAbsolutePath().normalize();

        System.out.println("[launcher] basedir=" + basedir);
        System.out.println("[launcher] 启动后端 order-service ...");
        ArthasMcpBackend order = ArthasMcpBackend.start("order-service");
        System.out.println("[launcher] 启动后端 payment ...");
        ArthasMcpBackend payment = ArthasMcpBackend.start("payment");

        String yaml = ""
                + "version: 1\n"
                + "backends:\n"
                + "  - name: order-service\n"
                + "    url: " + order.baseUrl() + "\n"
                + "    protocol: STREAMABLE\n"
                + "    auth:\n"
                + "      mode: NONE\n"
                + "    connectTimeoutMs: 5000\n"
                + "    callTimeoutMs: 30000\n"
                + "    maxConcurrentTasks: 5\n"
                + "  - name: payment\n"
                + "    url: " + payment.baseUrl() + "\n"
                + "    protocol: STREAMABLE\n"
                + "    auth:\n"
                + "      mode: NONE\n"
                + "    connectTimeoutMs: 5000\n"
                + "    callTimeoutMs: 30000\n"
                + "    maxConcurrentTasks: 5\n";

        Path cfg = basedir.resolve("config/backends-runtime.yaml");
        Files.createDirectories(cfg.getParent());
        Files.writeString(cfg, yaml, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

        System.out.println("=== SMOKE_DEMO_READY ===");
        System.out.println("order-service  baseUrl=" + order.baseUrl()
                + "  appPort=" + order.appPort() + "  mcpPort=" + order.mcpPort());
        System.out.println("payment        baseUrl=" + payment.baseUrl()
                + "  appPort=" + payment.appPort() + "  mcpPort=" + payment.mcpPort());
        System.out.println("runtime-config=" + cfg);
        System.out.println("order-app-health=http://127.0.0.1:" + order.appPort() + "/actuator/health");
        System.out.println("payment-app-health=http://127.0.0.1:" + payment.appPort() + "/actuator/health");
        System.out.println("=== 常驻中（kill 进程以退出）===");

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try { order.close(); } catch (Exception ignore) { /* 夹具 close 不抛 */ }
            try { payment.close(); } catch (Exception ignore) { /* 同上 */ }
            System.out.println("SMOKE_DEMO_STOPPED");
        }));

        Thread.currentThread().join(); // 常驻
    }

    private SmokeDemoLauncher() {
    }
}
```

### `smoke/SmokeMcpClient.java`

```
package com.arthas.gateway.smoke;

import com.arthas.gateway.testfixtures.McpClientHarness;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Content;
import io.modelcontextprotocol.spec.McpSchema.TextContent;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 冒烟 MCP 客户端（一次性端到端验证工具，非生产代码 / 非 spec 任务）。
 *
 * <p>用<b>官方 MCP Java SDK client</b>（合规客户端，走标准 Streamable HTTP，非裸 curl）连网关 {@code /mcp}，
 * 打印 {@code initialize} / {@code tools/list} 摘要，并对一组代表性工具逐一发起 {@code tools/call}，
 * 输出<b>完整请求入参与响应文本</b>（供测试报告归档）。覆盖：
 * <ul>
 *   <li>同步转发：{@code jvm} / {@code thread} / {@code ognl}（双 target）</li>
 *   <li>聚合：{@code dashboard}</li>
 *   <li>异步长任务：{@code watch} → 立即返 taskId → {@code task-get} / {@code task-list}</li>
 *   <li>错误用例：未知 target（S-ERR-5）、缺失必填 target（INVALID_PARAMS）</li>
 * </ul>
 *
 * <p>用法（须显式 JDK 21）：
 * <pre>{@code
 * java -cp target/test-classes:target/smoke-classes:<sdk-cp> \
 *      com.arthas.gateway.smoke.SmokeMcpClient [gatewayBaseUrl默认 http://127.0.0.1:8761/mcp]
 * }</pre>
 */
public final class SmokeMcpClient {

    /** 单条文本响应在控制台截断阈值（完整响应另见网关日志）。 */
    private static final int TEXT_CAP = 1500;
    private static final Pattern TASK_ID = Pattern.compile("\"taskId\"\\s*:\\s*\"(t-[0-9a-f]+)\"");

    private static final String ORDER_CLASS = "com.arthas.gateway.testfixtures.OrderService";
    // 目标 JVM（DemoBusinessApp）以 -Ddemo.slowMs=0 启动；读该系统属性既验 ognl 执行，又反验目标 JVM 身份
    private static final String OGNL_EXPR = "@java.lang.System@getProperty(\"demo.slowMs\")";

    public static void main(String[] args) throws Exception {
        String url = (args.length > 0) ? args[0] : "http://127.0.0.1:8761/mcp";
        System.out.println("########## MCP SMOKE CLIENT → " + url + " ##########");
        try (McpClientHarness h = new McpClientHarness(url)) {
            McpSchema.InitializeResult init = h.initialize();
            System.out.println("[INIT] protocolVersion=" + init.protocolVersion()
                    + "  serverInfo=" + init.serverInfo());

            McpSchema.ListToolsResult tools = h.listTools();
            int n = tools.tools() == null ? 0 : tools.tools().size();
            System.out.println("[TOOLS/LIST] count=" + n);
            if (tools.tools() != null) {
                for (McpSchema.Tool t : tools.tools()) {
                    String desc = t.description() == null ? "" : t.description().replaceAll("\\s+", " ");
                    if (desc.length() > 80) {
                        desc = desc.substring(0, 80) + "…";
                    }
                    System.out.println("    - " + t.name() + (desc.isEmpty() ? "" : "  :: " + desc));
                }
            }

            // —— 同步转发（双 target）——
            call(h, "arthas-gateway.list-targets", Map.of());
            call(h, "jvm", Map.of("target", "order-service"));
            call(h, "thread", Map.of("target", "payment"));
            call(h, "ognl", Map.of(
                    "target", "order-service",
                    "expression", OGNL_EXPR));

            // —— 聚合 ——
            call(h, "dashboard", Map.of("target", "order-service"));

            // —— 异步长任务：watch → taskId → task-get / task-list ——
            CallToolResult watch = call(h, "watch", Map.of(
                    "target", "order-service",
                    "classPattern", ORDER_CLASS,
                    "methodPattern", "hotMethod",
                    "numberOfExecutions", 5,
                    "timeout", 20));
            String taskId = extractTaskId(watch);
            if (taskId != null) {
                System.out.println("[smoke] watch 已受理，taskId=" + taskId + "，等待采集中…");
                Thread.sleep(2500);
                call(h, "arthas-gateway.task-get", Map.of("taskId", taskId));
                call(h, "arthas-gateway.task-list", Map.of());
            } else {
                System.out.println("[smoke] ⚠ 未解析到 taskId（watch 响应见上）");
            }

            // —— 错误用例 ——
            call(h, "jvm", Map.of("target", "no-such-target")); // S-ERR-5 backend_unreachable
            call(h, "jvm", Map.of());                            // INVALID_PARAMS 缺 target

            System.out.println("########## SMOKE CLIENT DONE ##########");
        }
    }

    /** 发起一次 tools/call 并打印完整请求/响应，返回结果供调用方进一步解析。 */
    static CallToolResult call(McpClientHarness h, String tool, Map<String, Object> args) {
        System.out.println("================ tools/call ================");
        System.out.println("REQUEST  tool=" + tool);
        System.out.println("REQUEST  args=" + argsJson(args));
        CallToolResult r;
        try {
            r = h.callTool(tool, args);
        } catch (McpError e) {
            // 网关对参数/路由错误返回 JSON-RPC error（如未知 target 的 S-ERR-5）——作为响应如实记录，不崩。
            System.out.println("RESPONSE jsonrpc-error(throw McpError): " + e.getMessage());
            return null;
        }
        System.out.println("RESPONSE isError=" + r.isError());
        StringBuilder buf = new StringBuilder();
        if (r.content() != null) {
            for (Content c : r.content()) {
                if (c instanceof TextContent tc && tc.text() != null) {
                    if (!buf.isEmpty()) {
                        buf.append('\n');
                    }
                    buf.append(tc.text());
                } else {
                    System.out.println("RESPONSE content[" + (buf.length()) + "]=<"
                            + c.getClass().getSimpleName() + ">");
                }
            }
        }
        String text = buf.toString();
        if (text.isEmpty()) {
            System.out.println("RESPONSE text=(空)");
        } else if (text.length() <= TEXT_CAP) {
            System.out.println("RESPONSE text:");
            System.out.println(text);
        } else {
            System.out.println("RESPONSE text (截断 " + TEXT_CAP + "/" + text.length() + " 字符):");
            System.out.println(text.substring(0, TEXT_CAP));
            System.out.println("…[已截断]");
        }
        return r;
    }

    static String extractTaskId(CallToolResult r) {
        if (r == null || r.content() == null) {
            return null;
        }
        for (Content c : r.content()) {
            if (c instanceof TextContent tc && tc.text() != null) {
                Matcher m = TASK_ID.matcher(tc.text());
                if (m.find()) {
                    return m.group(1);
                }
            }
        }
        return null;
    }

    /** 极简 Map→JSON（仅用于打印请求入参，值均为基本类型/字符串）。 */
    static String argsJson(Map<String, Object> args) {
        if (args == null || args.isEmpty()) {
            return "{}";
        }
        Map<String, Object> ordered = new LinkedHashMap<>(args);
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> e : ordered.entrySet()) {
            if (!first) {
                sb.append(", ");
            }
            sb.append('"').append(e.getKey()).append("\": ");
            Object v = e.getValue();
            if (v == null) {
                sb.append("null");
            } else if (v instanceof Number || v instanceof Boolean) {
                sb.append(v);
            } else {
                String s = v.toString();
                sb.append('"').append(s.replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
            }
            first = false;
        }
        return sb.append('}').toString();
    }

    private SmokeMcpClient() {
    }
}
```

### `smoke/SmokeWatchAsync.java`

```
package com.arthas.gateway.smoke;

import com.arthas.gateway.testfixtures.McpClientHarness;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Content;
import io.modelcontextprotocol.spec.McpSchema.TextContent;

import java.time.Duration;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * watch 异步能力专项冒烟（一次性验证工具，非生产代码 / 非 spec 任务）。
 *
 * <p>用官方 MCP SDK client 连网关，聚焦验证 arthas-gateway 的异步长任务通道：
 * <ul>
 *   <li>[1] <b>非阻塞</b>：watch 提交「接受耗时」毫秒级；提交期间并发一次同步 jvm，其耗时与独立值相当（证明 watch 后台执行、不阻塞网关）。
 *       随后轮询 task-get 至终态，记录 working→completed 转移 + createdAt/completedAt 时序。</li>
 *   <li>[2] <b>task-cancel</b>：提交一个自然不结束的长 watch，立即 cancel，task-get 确认 cancelled。</li>
 *   <li>[3] <b>跨 target 并发</b>：同时对 order-service / payment 提交 watch，task-list 展示双任务，均至 completed。</li>
 * </ul>
 * 完整 watch 结果结构（accessPoint/className/methodName/cost/value=入参+返回值）在终态 task-get 中打印。
 */
public final class SmokeWatchAsync {

    private static final String ORDER_CLASS = "com.arthas.gateway.testfixtures.OrderService";
    private static final Pattern TASK_ID = Pattern.compile("\"taskId\"\\s*:\\s*\"(t-[0-9a-f]+)\"");
    private static final Pattern STATUS = Pattern.compile("\"status\"\\s*:\\s*\"([A-Za-z]+)\"");

    private final McpClientHarness h;

    SmokeWatchAsync(McpClientHarness h) {
        this.h = h;
    }

    public static void main(String[] args) throws Exception {
        String url = (args.length > 0) ? args[0] : "http://127.0.0.1:8761/mcp";
        System.out.println("########## WATCH ASYNC SMOKE -> " + url + " ##########");
        try (McpClientHarness h = new McpClientHarness(url)) {
            McpSchema_InitEcho(h);
            SmokeWatchAsync t = new SmokeWatchAsync(h);
            t.call("arthas-gateway.list-targets", Map.of());
            t.nonBlockingAndLifecycle();
            t.cancelFlow();
            t.concurrentMultiTarget();
        }
        System.out.println("########## WATCH ASYNC SMOKE DONE ##########");
    }

    // ===== [1] 非阻塞 + 生命周期 =====
    void nonBlockingAndLifecycle() throws Exception {
        System.out.println("\n===== [1] 非阻塞 + 生命周期 =====");
        long t0 = System.nanoTime();
        CallToolResult acc = call("watch", Map.of(
                "target", "order-service",
                "classPattern", ORDER_CLASS,
                "methodPattern", "hotMethod",
                "numberOfExecutions", 30,
                "timeout", 30));
        long acceptMs = (System.nanoTime() - t0) / 1_000_000;
        String taskId = extractTaskId(acc);
        System.out.println("[1] >>> watch 接受耗时 = " + acceptMs + " ms  taskId=" + taskId);

        // 提交后立即做一次同步调用，测其耗时（若 watch 阻塞网关，此处会被拖慢）
        long s0 = System.nanoTime();
        callQuiet("jvm", Map.of("target", "payment"));
        long syncMs = (System.nanoTime() - s0) / 1_000_000;
        System.out.println("[1] >>> 同期同步 jvm(payment) 耗时 = " + syncMs
                + " ms  （独立基线约 110~400ms；相当 ⇒ 未被 watch 阻塞）");

        if (taskId != null) {
            pollLifecycle(taskId, Duration.ofSeconds(20));
        }
    }

    // ===== [2] task-cancel =====
    void cancelFlow() throws Exception {
        System.out.println("\n===== [2] task-cancel 流程 =====");
        CallToolResult acc = call("watch", Map.of(
                "target", "order-service",
                "classPattern", ORDER_CLASS,
                "methodPattern", "hotMethod",
                "numberOfExecutions", 500,
                "timeout", 60));
        String taskId = extractTaskId(acc);
        System.out.println("[2] >>> 长 watch 提交 taskId=" + taskId);
        if (taskId == null) {
            return;
        }
        Thread.sleep(400); // 让任务进入 working
        System.out.println("[2] cancel 前的 task-get：");
        call("arthas-gateway.task-get", Map.of("taskId", taskId));
        call("arthas-gateway.task-cancel", Map.of("taskId", taskId));
        Thread.sleep(600);
        System.out.println("[2] cancel 后的 task-get：");
        call("arthas-gateway.task-get", Map.of("taskId", taskId));
    }

    // ===== [3] 跨 target 并发 =====
    void concurrentMultiTarget() throws Exception {
        System.out.println("\n===== [3] 跨 target 并发 =====");
        CallToolResult a = callQuiet("watch", Map.of(
                "target", "order-service", "classPattern", ORDER_CLASS, "methodPattern", "hotMethod",
                "numberOfExecutions", 8, "timeout", 20));
        CallToolResult b = callQuiet("watch", Map.of(
                "target", "payment", "classPattern", ORDER_CLASS, "methodPattern", "hotMethod",
                "numberOfExecutions", 8, "timeout", 20));
        String ta = extractTaskId(a);
        String tb = extractTaskId(b);
        System.out.println("[3] >>> 并发提交  order-service=" + ta + "   payment=" + tb);
        if (ta != null) {
            pollLifecycle(ta, Duration.ofSeconds(20));
        }
        if (tb != null) {
            pollLifecycle(tb, Duration.ofSeconds(20));
        }
        System.out.println("[3] task-list：");
        call("arthas-gateway.task-list", Map.of());
    }

    // ===== 辅助 =====
    void pollLifecycle(String taskId, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        String prev = null;
        while (System.nanoTime() < deadline) {
            CallToolResult r = safeCall("arthas-gateway.task-get", Map.of("taskId", taskId));
            String body = textOf(r);
            Matcher m = STATUS.matcher(body);
            String status = m.find() ? m.group(1) : "?";
            if (!status.equals(prev)) {
                System.out.println("    [lifecycle] " + taskId + " -> " + status);
                prev = status;
            }
            if (!"working".equalsIgnoreCase(status)) {
                System.out.println("    [task-get 终态原文]\n" + cap(body, 1300));
                return;
            }
            Thread.sleep(400);
        }
        System.out.println("    [lifecycle] " + taskId + " 在 " + timeout + " 内未达终态");
    }

    CallToolResult call(String tool, Map<String, Object> args) {
        System.out.println("---- tools/call  tool=" + tool + "  args=" + SmokeMcpClient.argsJson(args));
        return printResult(safeCall(tool, args));
    }

    CallToolResult callQuiet(String tool, Map<String, Object> args) {
        return safeCall(tool, args);
    }

    CallToolResult safeCall(String tool, Map<String, Object> args) {
        try {
            return h.callTool(tool, args);
        } catch (McpError e) {
            System.out.println("    [jsonrpc-error] " + e.getMessage());
            return null;
        }
    }

    CallToolResult printResult(CallToolResult r) {
        if (r == null) {
            System.out.println("    (无结果)");
            return null;
        }
        System.out.println("    isError=" + r.isError() + "  text:\n" + cap(textOf(r), 900));
        return r;
    }

    static String textOf(CallToolResult r) {
        if (r == null || r.content() == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Content c : r.content()) {
            if (c instanceof TextContent tc && tc.text() != null) {
                if (!sb.isEmpty()) {
                    sb.append('\n');
                }
                sb.append(tc.text());
            }
        }
        return sb.toString();
    }

    static String extractTaskId(CallToolResult r) {
        Matcher m = TASK_ID.matcher(textOf(r));
        return m.find() ? m.group(1) : null;
    }

    static String cap(String s, int n) {
        return (s.length() <= n) ? s : s.substring(0, n) + " …[截断 " + n + "/" + s.length() + "]";
    }

    /** initialize 握手并打印 serverInfo（独立方法避免与字段初始化顺序耦合）。 */
    private static void McpSchema_InitEcho(McpClientHarness h) {
        try {
            var init = h.initialize();
            System.out.println("[INIT] protocolVersion=" + init.protocolVersion()
                    + "  serverInfo=" + init.serverInfo());
        } catch (Exception e) {
            System.out.println("[INIT] 失败: " + e);
        }
    }
}
```

## 前端源码（web/src/）

### `web/src/__tests__/api/adminClient.test.ts`

```
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { request, ApiError } from '../../api/adminClient'

// 004 T007：adminClient request 封装（同源 fetch + 结构化错误，admin-invariants INV-ERR-1）。
describe('adminClient request', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
  })

  it('200 JSON → 解析响应体', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      headers: new Headers({ 'content-type': 'application/json' }),
      json: async () => ({ hello: 'world' }),
    } as unknown as Response))
    const data = await request<{ hello: string }>('/backends')
    expect(data).toEqual({ hello: 'world' })
  })

  it('204 → undefined（无体）', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      ok: true,
      status: 204,
      headers: new Headers(),
    } as unknown as Response))
    expect(await request('/x', { method: 'DELETE' })).toBeUndefined()
  })

  it('非 2xx → 抛 ApiError（结构化错误体）', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      ok: false,
      status: 400,
      statusText: 'Bad Request',
      headers: new Headers({ 'content-type': 'application/json' }),
      json: async () => ({ error: 'name 重复', reason: 'duplicate_name' }),
    } as unknown as Response))
    await expect(request('/x', { method: 'POST' })).rejects.toMatchObject({
      name: 'ApiError',
      status: 400,
      message: 'name 重复',
      reason: 'duplicate_name',
    })
    await expect(request('/x')).rejects.toBeInstanceOf(ApiError)
  })

  it('请求路径以 /admin 为前缀（同源）', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      headers: new Headers({ 'content-type': 'application/json' }),
      json: async () => ({}),
    } as unknown as Response)
    vi.stubGlobal('fetch', fetchMock)
    await request('/backends')
    expect(fetchMock).toHaveBeenCalledWith(
      '/admin/backends',
      expect.objectContaining({ headers: expect.objectContaining({ 'Content-Type': 'application/json' }) }),
    )
  })
})
```

### `web/src/__tests__/App.test.ts`

```
import { mount } from '@vue/test-utils'
import { describe, it, expect } from 'vitest'
import App from '../App.vue'

// 004 T007：portal 根组件骨架（导航 + 路由出口）。RouterLink/RouterView stub（单元级，不引 vue-router 完整装配）。
describe('App 根组件', () => {
  it('渲染标题 arthas portal', () => {
    const wrapper = mount(App, { global: { stubs: ['RouterLink', 'RouterView'] } })
    expect(wrapper.find('h1').text()).toBe('arthas portal')
  })

  it('含两个导航入口（后端管理 / 任务导出）', () => {
    const wrapper = mount(App, { global: { stubs: ['RouterLink', 'RouterView'] } })
    const links = wrapper.findAllComponents({ name: 'RouterLink' })
    // stub 后组件名可能为 'router-link'；用 findAll('routerlink-stub') 兜底
    const navEntries = links.length || wrapper.findAll('routerlink-stub').length
    expect(navEntries).toBeGreaterThanOrEqual(2)
  })
})
```

### `web/src/__tests__/components/BackendForm.test.ts`

```
import { mount } from '@vue/test-utils'
import { describe, it, expect } from 'vitest'
import BackendForm from '../../components/BackendForm.vue'

// 004 T017：后端表单（新增提交 + 动态编辑禁用 INV-DYN-1）。

describe('BackendForm', () => {
  it('新增态：填表提交触发 submit 事件', async () => {
    const wrapper = mount(BackendForm)
    await wrapper.get('[data-testid="form-name"]').setValue('new-svc')
    await wrapper.get('[data-testid="form-url"]').setValue('http://h:8563')
    await wrapper.get('[data-testid="form-submit"]').trigger('submit')

    const events = wrapper.emitted('submit')
    expect(events).toHaveLength(1)
    expect(events![0][0]).toMatchObject({ name: 'new-svc', url: 'http://h:8563' })
  })

  it('动态后端编辑态：禁用提交 + 显示不可编辑警告 invDyn1', async () => {
    const wrapper = mount(BackendForm, {
      props: {
        initial: {
          name: 'dyn', source: 'DYNAMIC', state: 'ACTIVE', healthy: true, breaker: 'CLOSED',
          url: 'http://d:8563', protocol: 'STREAMABLE', authMode: 'BEARER',
          connectTimeoutMs: 5000, callTimeoutMs: 30000, maxConcurrentTasks: 5,
        },
      },
    })
    expect(wrapper.find('[data-testid="dynamic-warn"]').exists()).toBe(true)
    expect(wrapper.get('[data-testid="form-submit"]').attributes('disabled')).toBeDefined()
  })
})
```

### `web/src/__tests__/views/BackendListView.test.ts`

```
import { mount } from '@vue/test-utils'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import BackendListView from '../../views/BackendListView.vue'

// 004 T017：后端管理页（mock adminClient.listBackends，验证列表渲染 + summary + 错误）。
vi.mock('../../api/adminClient', () => ({
  listBackends: vi.fn(),
  createBackend: vi.fn(),
  updateBackend: vi.fn(),
  deleteBackend: vi.fn(),
  ApiError: class ApiError extends Error {
    constructor(public status: number, message: string, public reason?: string) {
      super(message)
      this.name = 'ApiError'
    }
  },
}))

import { listBackends, createBackend } from '../../api/adminClient'

const sampleBackend = {
  name: 'order-service', source: 'STATIC', state: 'ACTIVE', healthy: true, breaker: 'CLOSED',
  url: 'http://10.0.0.10:8563', protocol: 'STREAMABLE', authMode: 'NONE',
  connectTimeoutMs: 5000, callTimeoutMs: 30000, maxConcurrentTasks: 5,
}

describe('BackendListView', () => {
  beforeEach(() => vi.clearAllMocks())

  it('挂载时加载并渲染后端列表 + summary', async () => {
    ;(listBackends as ReturnType<typeof vi.fn>).mockResolvedValue({
      backends: [sampleBackend],
      summary: { total: 1, healthy: 1, unhealthy: 0 },
    })
    const wrapper = mount(BackendListView, { global: { stubs: ['HealthBadge'] } })
    await new Promise((r) => setTimeout(r, 10))

    expect(wrapper.find('[data-testid="summary"]').text()).toContain('共 1 个')
    expect(wrapper.findAll('[data-testid="backend-row"]')).toHaveLength(1)
    expect(wrapper.get('[data-testid="backend-row"]').text()).toContain('order-service')
  })

  it('加载失败显示错误提示', async () => {
    ;(listBackends as ReturnType<typeof vi.fn>).mockRejectedValue(
      new (await import('../../api/adminClient')).ApiError(500, '读取失败', 'admin_io_error'),
    )
    const wrapper = mount(BackendListView, { global: { stubs: ['HealthBadge'] } })
    await new Promise((r) => setTimeout(r, 10))
    expect(wrapper.find('[data-testid="error"]').text()).toContain('读取失败')
  })

  it('新增按钮打开表单，提交触发 createBackend', async () => {
    ;(listBackends as ReturnType<typeof vi.fn>).mockResolvedValue({
      backends: [], summary: { total: 0, healthy: 0, unhealthy: 0 },
    })
    ;(createBackend as ReturnType<typeof vi.fn>).mockResolvedValue(sampleBackend)
    const wrapper = mount(BackendListView, { global: { stubs: ['HealthBadge'] } })
    await new Promise((r) => setTimeout(r, 10))

    await wrapper.get('[data-testid="btn-add"]').trigger('click')
    expect(wrapper.find('[data-testid="backend-form"]').exists()).toBe(true)

    await wrapper.get('[data-testid="form-name"]').setValue('order-service')
    await wrapper.get('[data-testid="form-url"]').setValue('http://10.0.0.10:8563')
    await wrapper.get('[data-testid="form-submit"]').trigger('submit')
    await new Promise((r) => setTimeout(r, 10))

    expect(createBackend).toHaveBeenCalled()
  })
})
```

### `web/src/__tests__/views/TaskExportView.test.ts`

```
import { mount, flushPromises } from '@vue/test-utils'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import TaskExportView from '../../views/TaskExportView.vue'
import type { TaskSummaryDto } from '../../api/adminClient'

// 004 T025 + T040：任务导出页（单查询展示 + 错误）+ 任务列表区（自动查首页/过滤/分页/点项/空态/错误态）。
// DownloadButton stub（其点击由 adminClient.downloadTaskExport 单测覆盖）。
vi.mock('../../api/adminClient', () => ({
  exportTask: vi.fn(),
  downloadTaskExport: vi.fn(),
  listTasks: vi.fn(),
  ApiError: class ApiError extends Error {
    constructor(public status: number, message: string, public reason?: string) {
      super(message)
      this.name = 'ApiError'
    }
  },
}))

import { exportTask, listTasks, ApiError } from '../../api/adminClient'

function summary(taskId: string, status = 'COMPLETED'): TaskSummaryDto {
  return {
    taskId, tool: 'watch', target: 'order-service', status,
    createdAt: '2026-07-06T00:00:00Z', completedAt: '2026-07-06T00:00:05Z', isError: false,
  }
}

describe('TaskExportView', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    ;(listTasks as ReturnType<typeof vi.fn>).mockResolvedValue({ items: [], total: 0, page: 0, size: 20 })
  })

  // ===== 列表区（T040 增量）=====

  it('挂载时自动查任务列表首页', async () => {
    ;(listTasks as ReturnType<typeof vi.fn>).mockResolvedValue({
      items: [summary('t-list-1')], total: 1, page: 0, size: 20,
    })
    const wrapper = mount(TaskExportView, { global: { stubs: ['DownloadButton'] } })
    await flushPromises()

    expect(listTasks).toHaveBeenCalledWith({ page: 0, size: 20 })
    expect(wrapper.find('[data-testid="task-list"]').exists()).toBe(true)
    expect(wrapper.get('[data-testid="task-list"]').text()).toContain('t-list-1')
  })

  it('列表为空时显示空态（自验证反馈）', async () => {
    const wrapper = mount(TaskExportView, { global: { stubs: ['DownloadButton'] } })
    await flushPromises()
    expect(wrapper.find('[data-testid="task-list-empty"]').exists()).toBe(true)
  })

  it('列表加载失败显示错误态（自验证反馈）', async () => {
    ;(listTasks as ReturnType<typeof vi.fn>).mockRejectedValue(new ApiError(500, '服务器错误'))
    const wrapper = mount(TaskExportView, { global: { stubs: ['DownloadButton'] } })
    await flushPromises()
    expect(wrapper.find('[data-testid="task-list-error"]').exists()).toBe(true)
    expect(wrapper.get('[data-testid="task-list-error"]').text()).toContain('服务器错误')
  })

  it('status 过滤切换重查第一页', async () => {
    const wrapper = mount(TaskExportView, { global: { stubs: ['DownloadButton'] } })
    await flushPromises()
    await wrapper.get('[data-testid="status-filter"]').setValue('COMPLETED')
    await flushPromises()
    expect(listTasks).toHaveBeenLastCalledWith(expect.objectContaining({ status: 'COMPLETED', page: 0 }))
  })

  it('点列表项填 taskId 并触发查询', async () => {
    ;(listTasks as ReturnType<typeof vi.fn>).mockResolvedValue({
      items: [summary('t-pick')], total: 1, page: 0, size: 20,
    })
    ;(exportTask as ReturnType<typeof vi.fn>).mockResolvedValue({
      taskId: 't-pick', tool: 'watch', target: 'order-service', status: 'COMPLETED',
      createdAt: '2026-07-06T00:00:00Z', completedAt: '2026-07-06T00:00:05Z',
      isError: false, frames: ['frame-a'],
    })
    const wrapper = mount(TaskExportView, { global: { stubs: ['DownloadButton'] } })
    await flushPromises()

    await wrapper.get('[data-testid="task-row-t-pick"]').trigger('click')
    await flushPromises()

    expect(exportTask).toHaveBeenCalledWith('t-pick')
    expect(wrapper.find('[data-testid="task-result"]').exists()).toBe(true)
  })

  it('分页下一页递增 page 参数', async () => {
    // 满页（items.length == size）才允许下一页（不满页=末页，按钮 disabled）
    const items = Array.from({ length: 20 }, (_, i) => summary(`t${i}`))
    ;(listTasks as ReturnType<typeof vi.fn>).mockResolvedValue({
      items, total: 25, page: 0, size: 20,
    })
    const wrapper = mount(TaskExportView, { global: { stubs: ['DownloadButton'] } })
    await flushPromises()

    await wrapper.get('[data-testid="next-page"]').trigger('click')
    await flushPromises()
    expect(listTasks).toHaveBeenLastCalledWith(expect.objectContaining({ page: 1 }))
  })

  // ===== 单任务查询（T025 既有，保留）=====

  it('查询成功展示任务元信息 + frames 数量', async () => {
    ;(exportTask as ReturnType<typeof vi.fn>).mockResolvedValue({
      taskId: 't1', tool: 'watch', target: 'order-service', status: 'COMPLETED',
      createdAt: '2026-07-06T00:00:00Z', completedAt: '2026-07-06T00:00:05Z',
      isError: false, frames: ['frame-a', 'frame-b'],
    })
    const wrapper = mount(TaskExportView, { global: { stubs: ['DownloadButton'] } })

    await wrapper.get('[data-testid="task-input"]').setValue('t1')
    await wrapper.get('[data-testid="query-btn"]').trigger('click')
    await flushPromises()

    expect(wrapper.find('[data-testid="task-result"]').exists()).toBe(true)
    expect(wrapper.get('[data-testid="task-result"]').text()).toContain('frames: 2')
    expect(wrapper.get('[data-testid="task-result"]').text()).toContain('order-service')
  })

  it('查询失败（409 未完成）显示错误提示', async () => {
    ;(exportTask as ReturnType<typeof vi.fn>).mockRejectedValue(
      new ApiError(409, '任务未完成', 'task_not_completed'),
    )
    const wrapper = mount(TaskExportView, { global: { stubs: ['DownloadButton'] } })

    await wrapper.get('[data-testid="task-input"]').setValue('t-x')
    await wrapper.get('[data-testid="query-btn"]').trigger('click')
    await flushPromises()

    expect(wrapper.find('[data-testid="error"]').text()).toContain('任务未完成')
    expect(wrapper.find('[data-testid="task-result"]').exists()).toBe(false)
  })

  it('查询失败（404 不存在）显示错误', async () => {
    ;(exportTask as ReturnType<typeof vi.fn>).mockRejectedValue(
      new ApiError(404, '未知任务', 'task_not_found'),
    )
    const wrapper = mount(TaskExportView, { global: { stubs: ['DownloadButton'] } })

    await wrapper.get('[data-testid="task-input"]').setValue('nope')
    await wrapper.get('[data-testid="query-btn"]').trigger('click')
    await flushPromises()

    expect(wrapper.find('[data-testid="error"]').text()).toContain('未知任务')
  })
})
```

### `web/src/api/adminClient.ts`

```
// 004 portal 前端 → /admin API 客户端（research.md R6：同源 fetch，无 CORS）。
// 仅展示层（宪法原则六 / R13）：CRUD/导出/校验逻辑在后端 Java，前端只 fetch + render + download。

const BASE = '/admin'

/** 管理面结构化错误（HTTP 状态码 + 错误体，admin-invariants INV-ERR-1）。 */
export class ApiError extends Error {
  constructor(
    public status: number,
    message: string,
    public reason?: string,
    public available?: string[],
  ) {
    super(message)
    this.name = 'ApiError'
  }
}

/** 同源 fetch 封装：JSON 请求/响应、错误结构化传播。 */
export async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const res = await fetch(`${BASE}${path}`, {
    headers: { 'Content-Type': 'application/json', ...(init?.headers ?? {}) },
    ...init,
  })
  if (!res.ok) {
    let body: { error?: string; reason?: string; available?: string[] } = {}
    try {
      body = await res.json()
    } catch {
      /* 非 JSON 错误体 */
    }
    throw new ApiError(res.status, body.error ?? res.statusText, body.reason, body.available)
  }
  if (res.status === 204) return undefined as T
  const ct = res.headers.get('content-type') ?? ''
  if (ct.includes('application/json')) return res.json() as Promise<T>
  return (await res.text()) as unknown as T
}

// ===== 后端配置 CRUD（US1，admin-api-contract §1）=====

export interface BackendDto {
  name: string
  source: 'STATIC' | 'DYNAMIC'
  state: 'ACTIVE' | 'RETIRED'
  healthy: boolean
  breaker: 'OPEN' | 'CLOSED'
  url: string
  protocol: 'STREAMABLE' | 'STATELESS'
  authMode: 'NONE' | 'BEARER' | 'BASIC'
  connectTimeoutMs: number
  callTimeoutMs: number
  maxConcurrentTasks: number
}

export interface BackendSummary {
  total: number
  healthy: number
  unhealthy: number
}

export interface BackendListResponse {
  backends: BackendDto[]
  summary: BackendSummary
}

export interface CreateBackendRequest {
  name: string
  url: string
  protocol?: string
  authMode?: string
  token?: string
  username?: string
  password?: string
  connectTimeoutMs?: number
  callTimeoutMs?: number
  maxConcurrentTasks?: number
}

export interface UpdateBackendRequest {
  url?: string
  authMode?: string
  token?: string
  username?: string
  password?: string
  connectTimeoutMs?: number
  callTimeoutMs?: number
  maxConcurrentTasks?: number
}

function enc(name: string): string {
  return encodeURIComponent(name)
}

export function listBackends(): Promise<BackendListResponse> {
  return request<BackendListResponse>('/backends')
}

export function getBackend(name: string): Promise<BackendDto> {
  return request<BackendDto>(`/backends/${enc(name)}`)
}

export function createBackend(req: CreateBackendRequest): Promise<BackendDto> {
  return request<BackendDto>('/backends', { method: 'POST', body: JSON.stringify(req) })
}

export function updateBackend(name: string, req: UpdateBackendRequest): Promise<BackendDto> {
  return request<BackendDto>(`/backends/${enc(name)}`, { method: 'PUT', body: JSON.stringify(req) })
}

export function deleteBackend(name: string): Promise<void> {
  return request<void>(`/backends/${enc(name)}`, { method: 'DELETE' })
}

// ===== 任务结果导出（US2，admin-api-contract §2）=====

export interface TaskExportDto {
  taskId: string
  tool: string
  target: string
  status: string
  createdAt: string
  completedAt: string
  isError: boolean
  frames: string[]
}

export function exportTask(taskId: string): Promise<TaskExportDto> {
  return request<TaskExportDto>(`/tasks/${enc(taskId)}/export?format=json`)
}

/**
 * 触发浏览器原样下载（GET attachment，不经前端解析/重序列化，admin-invariants INV-EXP-1）。
 * 错误（404/409）由浏览器默认处理；查询态先用 {@link exportTask} 探测并友好提示。
 */
export function downloadTaskExport(taskId: string): void {
  const a = document.createElement('a')
  a.href = `${BASE}/tasks/${enc(taskId)}/export?format=json`
  a.download = `${taskId}.json`
  a.click()
}

// ===== 任务列表（增量，admin-api-contract §2 GET /admin/tasks，FR-015）=====

/** 任务摘要（无 frames，INV-LIST-1）。 */
export interface TaskSummaryDto {
  taskId: string
  tool: string
  target: string
  status: string
  createdAt: string
  completedAt: string
  isError: boolean
}

/** 列表分页响应（items 当前页 + total 过滤后总数，INV-LIST-2）。 */
export interface TaskSummaryPage {
  items: TaskSummaryDto[]
  total: number
  page: number
  size: number
}

export interface ListTasksParams {
  status?: string
  tool?: string
  target?: string
  page?: number
  size?: number
}

/** GET /admin/tasks 列表查询（status/tool/target 过滤 + page/size 分页）。 */
export function listTasks(params: ListTasksParams = {}): Promise<TaskSummaryPage> {
  const qs = new URLSearchParams()
  if (params.status) qs.set('status', params.status)
  if (params.tool) qs.set('tool', params.tool)
  if (params.target) qs.set('target', params.target)
  if (params.page != null) qs.set('page', String(params.page))
  if (params.size != null) qs.set('size', String(params.size))
  const query = qs.toString()
  return request<TaskSummaryPage>(`/tasks${query ? '?' + query : ''}`)
}
```

### `web/src/App.vue`

```
<script setup lang="ts">
// 004 portal 根组件：顶部导航 + 路由出口（后端管理 US1 / 任务导出 US2）。
</script>

<template>
  <div class="portal">
    <header class="portal-header">
      <h1>arthas portal</h1>
      <nav>
        <RouterLink to="/backends">后端管理</RouterLink>
        <RouterLink to="/tasks">任务导出</RouterLink>
      </nav>
    </header>
    <main class="portal-main">
      <RouterView />
    </main>
  </div>
</template>

<style>
:root {
  --primary: #2563eb;
  --primary-dark: #1d4ed8;
  --danger: #dc2626;
  --text: #1f2937;
  --text-muted: #6b7280;
  --bg: #f3f4f6;
  --card: #ffffff;
  --border: #e5e7eb;
  --shadow-sm: 0 1px 3px rgba(0, 0, 0, 0.08);
  --shadow-md: 0 4px 12px rgba(37, 99, 235, 0.15);
  --radius: 0.5rem;
}
* { box-sizing: border-box; }
body { margin: 0; background: var(--bg); color: var(--text); font-family: system-ui, -apple-system, 'Segoe UI', 'PingFang SC', 'Microsoft YaHei', sans-serif; }
.portal { max-width: 1200px; margin: 0 auto; padding: 1.5rem; }
.portal-header {
  display: flex; align-items: center; gap: 2rem;
  background: linear-gradient(135deg, #1e3a8a 0%, #2563eb 100%);
  color: #fff; padding: 1rem 1.5rem; border-radius: var(--radius);
  box-shadow: var(--shadow-md); margin-bottom: 1.5rem;
}
.portal-header h1 { font-size: 1.25rem; margin: 0; font-weight: 600; letter-spacing: 0.02em; }
.portal-header nav { display: flex; gap: 0.25rem; margin-left: auto; }
.portal-header nav a {
  color: rgba(255, 255, 255, 0.85); text-decoration: none;
  padding: 0.4rem 0.95rem; border-radius: 0.4rem; font-size: 0.9rem;
  transition: all 0.15s ease;
}
.portal-header nav a:hover { background: rgba(255, 255, 255, 0.15); color: #fff; }
.portal-header nav a.router-link-active { background: rgba(255, 255, 255, 0.25); color: #fff; font-weight: 600; }
section h2 { font-size: 1.4rem; margin: 0 0 0.5rem 0; font-weight: 600; color: var(--text); }
</style>
```

### `web/src/components/BackendForm.vue`

```
<script setup lang="ts">
// 004 后端新增/编辑表单（admin-api-contract §1）。动态后端不可改（INV-DYN-1）→ 编辑态禁用动态。
import { reactive, watch } from 'vue'
import type { BackendDto, CreateBackendRequest, UpdateBackendRequest } from '../api/adminClient'

const props = defineProps<{
  initial?: BackendDto | null
}>()

const emit = defineEmits<{
  (e: 'submit', req: CreateBackendRequest | UpdateBackendRequest): void
  (e: 'cancel'): void
}>()

const editing = !!props.initial
const dynamic = props.initial?.source === 'DYNAMIC'

const form = reactive({
  name: props.initial?.name ?? '',
  url: props.initial?.url ?? '',
  protocol: props.initial?.protocol ?? 'STREAMABLE',
  authMode: props.initial?.authMode ?? 'NONE',
  token: '',
  maxConcurrentTasks: props.initial?.maxConcurrentTasks ?? 5,
})

watch(
  () => props.initial,
  (init) => {
    if (init) {
      form.name = init.name
      form.url = init.url
      form.protocol = init.protocol
      form.authMode = init.authMode
      form.maxConcurrentTasks = init.maxConcurrentTasks
    }
  },
)

function onSubmit() {
  if (editing) {
    if (dynamic) return // 动态不可改（INV-DYN-1）
    emit('submit', { url: form.url, authMode: form.authMode, maxConcurrentTasks: form.maxConcurrentTasks })
  } else {
    emit('submit', {
      name: form.name,
      url: form.url,
      protocol: form.protocol,
      authMode: form.authMode,
      token: form.token || undefined,
      maxConcurrentTasks: form.maxConcurrentTasks,
    })
  }
}
</script>

<template>
  <form class="backend-form" data-testid="backend-form" @submit.prevent="onSubmit">
    <label>名称<input v-model="form.name" :disabled="editing" required data-testid="form-name" /></label>
    <label>URL<input v-model="form.url" required data-testid="form-url" /></label>
    <label>协议
      <select v-model="form.protocol" :disabled="dynamic" data-testid="form-protocol">
        <option>STREAMABLE</option>
        <option>STATELESS</option>
      </select>
    </label>
    <label>认证
      <select v-model="form.authMode" :disabled="dynamic" data-testid="form-auth">
        <option>NONE</option>
        <option>BEARER</option>
        <option>BASIC</option>
      </select>
    </label>
    <label v-if="!editing || form.authMode === 'BEARER'">Token<input v-model="form.token" data-testid="form-token" /></label>
    <label>并发上限<input
        v-model.number="form.maxConcurrentTasks"
        type="number"
        min="1"
        max="5"
        :disabled="dynamic"
        data-testid="form-max"
      /></label>
    <div v-if="dynamic" class="dynamic-warn" data-testid="dynamic-warn">动态后端不可编辑（须先删再 ensure）</div>
    <div class="actions">
      <button type="submit" :disabled="dynamic" data-testid="form-submit">{{ editing ? '保存' : '新增' }}</button>
      <button type="button" data-testid="form-cancel" @click="emit('cancel')">取消</button>
    </div>
  </form>
</template>

<style scoped>
.backend-form {
  display: grid; gap: 0.75rem; padding: 1.2rem; margin-top: 1rem;
  background: var(--card); border-radius: var(--radius); border: 1px solid var(--border);
  box-shadow: var(--shadow-sm);
}
.backend-form label { display: flex; gap: 0.6rem; align-items: center; font-size: 0.85rem; color: var(--text-muted); }
.backend-form input,
.backend-form select {
  flex: 1; padding: 0.45rem 0.55rem; border: 1px solid var(--border);
  border-radius: 0.35rem; font-size: 0.9rem; background: #fff;
}
.backend-form input:focus,
.backend-form select:focus { outline: none; border-color: var(--primary); box-shadow: 0 0 0 3px rgba(37, 99, 235, 0.12); }
.dynamic-warn { color: var(--danger); font-size: 0.82rem; background: #fef2f2; padding: 0.45rem 0.65rem; border-radius: 0.35rem; border: 1px solid #fecaca; }
.actions { display: flex; gap: 0.5rem; margin-top: 0.25rem; }
.actions button { padding: 0.5rem 1.1rem; font-size: 0.85rem; border-radius: 0.4rem; cursor: pointer; border: 1px solid var(--border); transition: all 0.15s ease; }
[data-testid='form-submit'] { background: var(--primary); color: #fff; border-color: var(--primary); }
[data-testid='form-submit']:hover { background: var(--primary-dark); border-color: var(--primary-dark); }
[data-testid='form-cancel'] { background: var(--card); color: var(--text); }
[data-testid='form-cancel']:hover { background: #f3f4f6; }
</style>
```

### `web/src/components/DownloadButton.vue`

```
<script setup lang="ts">
// 004 任务结果下载按钮（触发浏览器原样下载 attachment，INV-EXP-1）。
import { downloadTaskExport } from '../api/adminClient'

defineProps<{
  taskId: string
  disabled?: boolean
}>()
</script>

<template>
  <button
    :disabled="disabled || !taskId"
    data-testid="download-btn"
    @click="downloadTaskExport(taskId)"
  >
    下载 JSON
  </button>
</template>
```

### `web/src/components/HealthBadge.vue`

```
<script setup lang="ts">
// 004 健康徽标（SC-003：healthy/breaker 可视化）。
defineProps<{
  healthy: boolean
  breaker: 'OPEN' | 'CLOSED'
}>()
</script>

<template>
  <span
    class="badge"
    :class="healthy ? 'badge-ok' : 'badge-bad'"
    :data-testid="`health-${healthy ? 'ok' : 'bad'}`"
  >
    {{ healthy ? '健康' : '异常' }} · {{ breaker }}
  </span>
</template>

<style scoped>
.badge {
  display: inline-flex; align-items: center; gap: 0.35rem;
  padding: 0.2rem 0.65rem; border-radius: 1rem;
  font-size: 0.78rem; font-weight: 500;
}
.badge::before { content: ''; width: 6px; height: 6px; border-radius: 50%; background: currentColor; }
.badge-ok { background: #d1fae5; color: #065f46; }
.badge-bad { background: #fee2e2; color: #991b1b; }
</style>
```

### `web/src/main.ts`

```
import { createApp } from 'vue'
import App from './App.vue'
import router from './router'

// 004 portal 入口：挂载 Vue SPA（浏览器访问网关根加载，research.md R1）。
createApp(App).use(router).mount('#app')
```

### `web/src/router.ts`

```
import { createRouter, createWebHistory } from 'vue-router'

// 004 portal 路由（research.md R10）：后端管理（US1）/ 任务导出（US2）。
// createWebHistory：SPA history 模式，Spring Boot 服务 static、根路径载入 index.html。
// views 懒加载（动态 import）；占位组件在 Phase 3/4 替换为真实实现。
const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', redirect: '/backends' },
    { path: '/backends', name: 'backends', component: () => import('./views/BackendListView.vue') },
    { path: '/tasks', name: 'tasks', component: () => import('./views/TaskExportView.vue') },
  ],
})

export default router
```

### `web/src/views/BackendListView.vue`

```
<script setup lang="ts">
// 004 US1 后端管理页（admin-api-contract §1）。列表 + 增删改 + 健康徽标 + 错误提示。
import { onMounted, ref } from 'vue'
import {
  listBackends, createBackend, updateBackend, deleteBackend,
  type BackendDto, type BackendListResponse,
  type CreateBackendRequest, type UpdateBackendRequest, ApiError,
} from '../api/adminClient'
import BackendForm from '../components/BackendForm.vue'
import HealthBadge from '../components/HealthBadge.vue'

const data = ref<BackendListResponse | null>(null)
const error = ref('')
const showForm = ref(false)
const editing = ref<BackendDto | null>(null)

async function refresh() {
  try {
    data.value = await listBackends()
    error.value = ''
  } catch (e) {
    error.value = e instanceof ApiError ? `${e.message}（${e.reason ?? e.status}）` : '加载失败'
  }
}

onMounted(refresh)

function openAdd() {
  editing.value = null
  showForm.value = true
}
function openEdit(b: BackendDto) {
  editing.value = b
  showForm.value = true
}

async function onSubmit(req: CreateBackendRequest | UpdateBackendRequest) {
  try {
    if (editing.value) {
      await updateBackend(editing.value.name, req as UpdateBackendRequest)
    } else {
      await createBackend(req as CreateBackendRequest)
    }
    showForm.value = false
    await refresh()
  } catch (e) {
    error.value = e instanceof ApiError ? `${e.message}（${e.reason ?? e.status}）` : '保存失败'
  }
}

async function onDelete(b: BackendDto) {
  if (!confirm(`删除后端 ${b.name}？`)) return
  try {
    await deleteBackend(b.name)
    await refresh()
  } catch (e) {
    error.value = e instanceof ApiError ? `${e.message}（${e.reason ?? e.status}）` : '删除失败'
  }
}
</script>

<template>
  <section data-testid="backends-view">
    <h2>后端管理</h2>
    <p v-if="data?.summary" class="summary" data-testid="summary">
      共 {{ data.summary.total }} 个（健康 {{ data.summary.healthy }} / 异常 {{ data.summary.unhealthy }}）
    </p>
    <div v-if="error" class="error" data-testid="error">{{ error }}</div>
    <table class="backends-table" data-testid="backends-table">
      <thead><tr><th>名称</th><th>来源</th><th>健康</th><th>URL</th><th>认证</th><th>操作</th></tr></thead>
      <tbody>
        <tr v-for="b in data?.backends ?? []" :key="b.name" data-testid="backend-row">
          <td>{{ b.name }}</td>
          <td>{{ b.source }}</td>
          <td><HealthBadge :healthy="b.healthy" :breaker="b.breaker" /></td>
          <td>{{ b.url }}</td>
          <td>{{ b.authMode }}</td>
          <td>
            <button :data-testid="`edit-${b.name}`" @click="openEdit(b)">编辑</button>
            <button :data-testid="`del-${b.name}`" @click="onDelete(b)">删除</button>
          </td>
        </tr>
      </tbody>
    </table>
    <button data-testid="btn-add" @click="openAdd">新增后端</button>
    <BackendForm v-if="showForm" :initial="editing" @submit="onSubmit" @cancel="showForm = false" />
  </section>
</template>

<style scoped>
.summary { color: var(--text-muted); font-size: 0.9rem; margin: 0.5rem 0 1rem 0; }
.error { color: var(--danger); background: #fef2f2; padding: 0.6rem 0.85rem; border-radius: 0.4rem; border: 1px solid #fecaca; margin: 0.75rem 0; font-size: 0.9rem; }
.backends-table {
  width: 100%; border-collapse: separate; border-spacing: 0; margin: 1rem 0;
  background: var(--card); border-radius: var(--radius); overflow: hidden;
  box-shadow: var(--shadow-sm);
}
.backends-table th {
  background: #f9fafb; color: var(--text-muted); font-weight: 600; font-size: 0.78rem;
  text-transform: uppercase; letter-spacing: 0.05em; padding: 0.75rem 0.85rem;
  text-align: left; border-bottom: 1px solid var(--border);
}
.backends-table td { padding: 0.7rem 0.85rem; border-bottom: 1px solid var(--border); font-size: 0.9rem; }
.backends-table tbody tr:hover { background: #f9fafb; }
.backends-table tbody tr:last-child td { border-bottom: none; }
button {
  padding: 0.35rem 0.8rem; border: 1px solid var(--border); background: var(--card);
  border-radius: 0.35rem; cursor: pointer; font-size: 0.85rem; color: var(--text);
  margin-right: 0.3rem; transition: all 0.15s ease;
}
button:hover { background: #f3f4f6; border-color: #d1d5db; }
[data-testid='btn-add'] { background: var(--primary); color: #fff; border-color: var(--primary); margin-top: 0.5rem; padding: 0.5rem 1.1rem; }
[data-testid='btn-add']:hover { background: var(--primary-dark); border-color: var(--primary-dark); }
</style>
```

### `web/src/views/TaskExportView.vue`

```
<script setup lang="ts">
// 004 US2 任务导出页（admin-api-contract §2）+ 增量任务列表区（FR-015）。
// 上方：最近任务列表（自动查首页 + status 过滤 + 分页 + 点项填 taskId 衔接导出）。
// 下方：按 taskId 查询 + 下载（attachment 原样）。
import { ref, onMounted } from 'vue'
import { exportTask, listTasks, ApiError, type TaskExportDto, type TaskSummaryDto } from '../api/adminClient'
import DownloadButton from '../components/DownloadButton.vue'

// ===== 列表区状态 =====
const tasks = ref<TaskSummaryDto[]>([])
const total = ref(0)
const page = ref(0)
const size = ref(20)
const statusFilter = ref('')
const listLoading = ref(false)
const listError = ref('')

// ===== 单任务查询状态（既有）=====
const taskId = ref('')
const dto = ref<TaskExportDto | null>(null)
const error = ref('')

async function loadList() {
  listLoading.value = true
  listError.value = ''
  try {
    const params: { page: number; size: number; status?: string } = { page: page.value, size: size.value }
    if (statusFilter.value) params.status = statusFilter.value
    const res = await listTasks(params)
    tasks.value = res.items
    total.value = res.total
  } catch (e) {
    tasks.value = []
    total.value = 0
    listError.value = e instanceof ApiError ? `${e.message}（${e.reason ?? e.status}）` : '列表加载失败'
  } finally {
    listLoading.value = false
  }
}

function onStatusChange(e: Event) {
  statusFilter.value = (e.target as HTMLSelectElement).value
  page.value = 0
  loadList()
}

function nextPage() {
  page.value++
  loadList()
}

function prevPage() {
  if (page.value > 0) {
    page.value--
    loadList()
  }
}

/** 点列表项 → 填 taskId + 触发查询，衔接既有导出流。 */
function pickTask(t: TaskSummaryDto) {
  taskId.value = t.taskId
  query()
}

async function query() {
  if (!taskId.value.trim()) return
  try {
    dto.value = await exportTask(taskId.value.trim())
    error.value = ''
  } catch (e) {
    dto.value = null
    error.value = e instanceof ApiError ? `${e.message}（${e.reason ?? e.status}）` : '查询失败'
  }
}

onMounted(() => loadList())
</script>

<template>
  <section data-testid="tasks-view">
    <h2>任务导出</h2>

    <!-- ===== 列表区（增量 FR-015）===== -->
    <div class="list-section">
      <div class="list-header">
        <h3>最近任务 <span class="count">（{{ total }}）</span></h3>
        <select data-testid="status-filter" :value="statusFilter" @change="onStatusChange">
          <option value="">全部状态</option>
          <option value="WORKING">WORKING</option>
          <option value="COMPLETED">COMPLETED</option>
          <option value="FAILED">FAILED</option>
          <option value="CANCELLED">CANCELLED</option>
        </select>
      </div>

      <div v-if="listLoading" class="state-msg" data-testid="task-list-loading">加载中…</div>
      <div v-else-if="listError" class="error" data-testid="task-list-error">{{ listError }}</div>
      <div v-else-if="tasks.length === 0" class="state-msg" data-testid="task-list-empty">暂无任务</div>
      <table v-else data-testid="task-list" class="task-table">
        <thead>
          <tr>
            <th>taskId</th><th>工具</th><th>目标</th><th>状态</th><th>创建时间</th><th>错误</th>
          </tr>
        </thead>
        <tbody>
          <tr
            v-for="t in tasks"
            :key="t.taskId"
            :data-testid="`task-row-${t.taskId}`"
            class="task-row"
            @click="pickTask(t)"
          >
            <td class="mono">{{ t.taskId }}</td>
            <td>{{ t.tool }}</td>
            <td>{{ t.target }}</td>
            <td><span class="status-badge" :class="`status-${t.status.toLowerCase()}`">{{ t.status }}</span></td>
            <td class="mono small">{{ t.createdAt }}</td>
            <td>{{ t.isError ? '是' : '—' }}</td>
          </tr>
        </tbody>
      </table>

      <div class="pagination">
        <button data-testid="prev-page" :disabled="page === 0" @click="prevPage">上一页</button>
        <span data-testid="page-info">第 {{ page + 1 }} 页 / 共 {{ Math.max(1, Math.ceil(total / size)) }} 页</span>
        <button data-testid="next-page" :disabled="tasks.length < size" @click="nextPage">下一页</button>
      </div>
    </div>

    <!-- ===== 单任务查询 + 导出（既有）===== -->
    <h3 class="query-title">按 taskId 查询 / 导出</h3>
    <div class="input-row">
      <input
        v-model="taskId"
        placeholder="taskId（如 t-abc123）"
        data-testid="task-input"
      />
      <button data-testid="query-btn" @click="query">查询</button>
    </div>
    <div v-if="error" class="error" data-testid="error">{{ error }}</div>
    <div v-if="dto" class="result" data-testid="task-result">
      <p>任务 <strong>{{ dto.taskId }}</strong>（{{ dto.tool }} @ {{ dto.target }}）— {{ dto.status }}</p>
      <p>frames: {{ dto.frames.length }} 条<span v-if="dto.isError">（业务错误标志 isError=true）</span></p>
      <DownloadButton :task-id="dto.taskId" />
    </div>
  </section>
</template>

<style scoped>
.list-section {
  margin: 1rem 0 1.5rem; padding: 1.1rem 1.2rem; background: var(--card);
  border: 1px solid var(--border); border-radius: var(--radius); box-shadow: var(--shadow-sm);
}
.list-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 0.75rem; }
.list-header h3 { margin: 0; font-size: 1rem; color: var(--text); }
.count { color: var(--text-light); font-weight: normal; }
.list-header select {
  padding: 0.35rem 0.55rem; border: 1px solid var(--border); border-radius: 0.4rem;
  background: #fff; font-size: 0.85rem; cursor: pointer;
}
.list-header select:focus { outline: none; border-color: var(--primary); }
.state-msg { padding: 1.2rem; text-align: center; color: var(--text-light); font-size: 0.9rem; }
.task-table { width: 100%; border-collapse: collapse; font-size: 0.88rem; }
.task-table th {
  text-align: left; padding: 0.55rem 0.6rem; background: #f9fafb; color: var(--text);
  font-weight: 600; border-bottom: 1px solid var(--border); font-size: 0.82rem;
}
.task-table td { padding: 0.5rem 0.6rem; border-bottom: 1px solid #f3f4f6; color: var(--text); }
.task-row { cursor: pointer; transition: background 0.12s ease; }
.task-row:hover { background: #eff6ff; }
.mono { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: 0.82rem; }
.small { font-size: 0.78rem; color: var(--text-light); }
.status-badge {
  display: inline-block; padding: 0.15rem 0.5rem; border-radius: 1rem;
  font-size: 0.74rem; font-weight: 500;
}
.status-completed { background: #d1fae5; color: #065f46; }
.status-working { background: #fef3c7; color: #92400e; }
.status-failed { background: #fee2e2; color: #991b1b; }
.status-cancelled { background: #e5e7eb; color: #4b5563; }
.pagination { display: flex; align-items: center; gap: 0.85rem; margin-top: 0.85rem; font-size: 0.85rem; color: var(--text-light); }
.pagination button {
  padding: 0.3rem 0.85rem; border: 1px solid var(--border); border-radius: 0.4rem;
  background: #fff; cursor: pointer; font-size: 0.82rem; transition: all 0.15s ease;
}
.pagination button:hover:not(:disabled) { border-color: var(--primary); color: var(--primary); }
.pagination button:disabled { opacity: 0.4; cursor: not-allowed; }
.query-title { margin: 1.5rem 0 0.5rem; font-size: 1rem; color: var(--text); }
.input-row { display: flex; gap: 0.5rem; margin: 0.5rem 0; }
.input-row input {
  flex: 1; padding: 0.55rem 0.75rem; border: 1px solid var(--border);
  border-radius: 0.4rem; font-size: 0.9rem; background: #fff;
}
.input-row input:focus { outline: none; border-color: var(--primary); box-shadow: 0 0 0 3px rgba(37, 99, 235, 0.12); }
.input-row button {
  padding: 0.55rem 1.3rem; background: var(--primary); color: #fff;
  border: none; border-radius: 0.4rem; cursor: pointer; font-size: 0.9rem; transition: all 0.15s ease;
}
.input-row button:hover { background: var(--primary-dark); }
.error { color: var(--danger); background: #fef2f2; padding: 0.6rem 0.85rem; border-radius: 0.4rem; border: 1px solid #fecaca; margin: 0.75rem 0; font-size: 0.9rem; }
.result {
  margin-top: 1rem; padding: 1.2rem; background: var(--card);
  border: 1px solid var(--border); border-radius: var(--radius); box-shadow: var(--shadow-sm);
}
.result p { margin: 0.4rem 0; color: var(--text); font-size: 0.9rem; }
.result p strong { color: var(--primary); }
</style>
```

## 测试夹具资源

### `src/test/resources/mockito-extensions/org.mockito.plugins.MockMaker`
```
mock-maker-inline
```

