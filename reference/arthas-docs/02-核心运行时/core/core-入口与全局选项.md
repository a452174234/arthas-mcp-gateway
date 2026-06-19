# core · 入口与全局选项

> 覆盖源码包：`core/`（根级）、`core/server/`、`core/server/instrument/`、`core/security/`
> 解决：**arthas agent 被加载后，core 如何完成核心初始化？全局开关与安全认证在哪？**

这一篇是 [`agent`](../../01-启动与attach/agent.md) 反射调用的终点，也是后续 `shell`/`command`/`advisor` 能跑起来的前提。

---

## 一、核心单例：`ArthasBootstrap`

### `ArthasBootstrap`
`core/src/main/java/com/taobao/arthas/core/server/ArthasBootstrap.java` — **arthas 核心的单例入口与资源管理中心**。

`agent`/`arthas-agent-attach` 通过反射调用它完成绑定。它负责：

- 初始化 [`spy/SpyAPI`](../../01-启动与attach/spy.md)（注入 BootstrapClassLoader）；
- 按需增强 `ClassLoader`（保证 spy 可见）；
- 启动 Shell 服务（Telnet/HTTP）、按需启动 MCP；
- 初始化会话管理器、历史记录、调度线程池；
- 通过 `ServiceLoader` 加载外部命令（[`arthas-demo-external-command`](https://github.com/alibaba/arthas) 那种扩展点）；
- 注册 shutdown hook。

**核心方法：**
- **`getInstance(Instrumentation inst, Map<String,String> args)`** / `getInstance(inst, configureString)` — 单例获取（agent 反射入口）。
- **`initSpy()`** — 把 `arthas-spy.jar` 追加到 BootstrapClassLoader（详见 [spy.md](../../01-启动与attach/spy.md)）。
- **`enhanceClassLoader()`** — 增强 `java.lang.ClassLoader`（见下方 `ClassLoader_Instrument`）。
- **`bind(Configure configure)`** — 绑定并启动 Telnet/HTTP/MCP 服务。
- `reset()` — 重置所有增强（`reset` 命令底层）。
- `destroy()` — 销毁，清理资源。
- `getTransformerManager()` / `getShellServer()` / `getSessionManager()` / `getScheduledExecutorService()` — 各管理器访问器。

**初始化顺序（要点）：**
解析参数 → `Configure` → `initSpy()` → `enhanceClassLoader()` → 初始化日志/各类管理器 → 加载外部命令 → 启动 Shell → （可选）启动 MCP → `SpyAPI.init()`。

---

## 二、ClassLoader 增强

### `ClassLoader_Instrument`
`core/src/main/java/com/taobao/arthas/core/server/instrument/ClassLoader_Instrument.java`

- 解决 issue #1596：部分被魔改的 ClassLoader 连 BootstrapClassLoader 都加载不到 `java.arthas.SpyAPI`。
- 做法：增强 `java.lang.ClassLoader.loadClass(String)`，对 `java.arthas.*` 开头的类改用 ExtensionClassLoader 加载。
- `loadClass(String name)` — 拦截点。

> 通常无需改动；只有遇到"spy 加载不到"的诡异环境问题才需要看这里。

---

## 三、安全认证：`security` 包

`core/src/main/java/com/taobao/arthas/core/security/`

arthas 的访问控制：支持用户名/密码、Bearer Token、本地连接免认证，并可对接 JAAS。

- **`SecurityAuthenticator`** — 认证器接口。`needLogin()`、`login(Principal)`、`logout(Subject)`、`getUserRoles(Subject)`、`setName/getName`、`setRoleClassNames`。
- **`SecurityAuthenticatorImpl`** — 默认实现。
  - `SecurityAuthenticatorImpl(username, password)` — 只给 username 会自动生成随机密码。
  - `login(Principal)` — 分支处理：`LocalConnectionPrincipal`（本地直通）、`BasicPrincipal`（校验账密）、`BearerPrincipal`（token 当密码）。
  - `needLogin()` — username 与 password 都非空才需要登录。
- **`AuthUtils`** — 工具。`localPrincipal(ctx)`、`isLocalConnection(ctx)`（判断 127.0.0.1）。
- **`BasicPrincipal`** / **`BearerPrincipal`** / **`LocalConnectionPrincipal`** — 三种认证主体（封装账密 / token / 本地标识）。

> 配套：`basic1000/AuthCommand`（`auth` 命令）让用户在会话内登录。

---

## 四、启动入口：`Arthas`

### `Arthas`
`core/src/main/java/com/taobao/arthas/core/Arthas.java` — **通过 attach API 挂载 arthas 的入口类**（与 `arthas-boot.jar`/`arthas-agent-attach` 不同的另一条路径，更底层）。

- `main(String[] args)` — 入口：解析参数 → attach 目标 JVM → `loadAgent`。
- `parse(String[] args)` — 解析为 `Configure`（`--pid`/`--core`/`--agent`/`--target-ip`/`--telnet-port`/`--http-port`/`--username`/`--password`/`--tunnel-server`/`--agent-id`/`--stat-url` 等）。
- `attachAgent(Configure)` — `VirtualMachine.attach(pid)` → `loadAgent(agentJar, args)`（参数 URL 编码）。

> 这条路径常用于"已知 PID、用 `java -jar arthas-core.jar` 直接 attach"或脚本化场景。常规用户走 `arthas-boot.jar` 不会直接碰到它。

---

## 五、全局选项：`GlobalOptions` / `Option`

`core/src/main/java/com/taobao/arthas/core/GlobalOptions.java` + `Option.java`

对应 `options` 命令（`basic1000/OptionsCommand`）可在线修改的全局开关。

### `GlobalOptions`（关键字段）
| 字段 | 含义 | 默认 |
|---|---|---|
| `isUnsafe` | 是否允许增强 JDK 核心类（高风险） | `false` |
| `isDump` | 是否 dump 被增强的类 | `false` |
| `isBatchReTransform` | 是否批量增强类 | `true` |
| `isUsingJson` | 对象输出是否用 JSON | `false` |
| `objectSizeLimit` | `ObjectView` 输出大小上限 | 10MB |
| `isDisableSubClass` | 是否关闭子类匹配 | `false` |
| `isSaveResult` | 是否保存命令结果到日志 | `false` |
| `jobTimeout` | job 超时 | `1d` |
| `printParentFields` | 是否打印父类字段 | `true` |
| `strict` | OGNL 严格模式 | `true` |

- `updateOnglStrict(boolean)` — 通过 `Unsafe` 改 OGNL 静态字段来切换严格模式。

### `Option`（注解）
标记 `GlobalOptions` 各字段，供 `options` 命令反射展示：`level()`、`name()`、`summary()`、`description()`。

---

## 与其它模块的关系

- 上游：[`agent`](../../01-启动与attach/agent.md) / [`arthas-agent-attach`](../../01-启动与attach/arthas-agent-attach.md) 反射调用 `ArthasBootstrap.getInstance()`。
- 下游：启动 [`shell`](./core-shell交互系统.md) → 调度 [`command`](./core-命令系统.md) → 触发 [`advisor`](./core-字节码增强.md)。
- 配置由 [`config/Configure`](./core-支撑层.md) 承载。

## 定位提示

> "agent attach 之后第一件事做什么？" → `ArthasBootstrap.initSpy()`。
> "`options` 命令改的那些开关定义在哪？" → `GlobalOptions`。
> "arthas 的用户名密码认证怎么实现的？" → `security/SecurityAuthenticatorImpl.login()`。
