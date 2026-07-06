# portal 异步任务列表查询设计（004 增量）

> 日期：2026-07-07
> 归档：`specs/004-portal-backend-management/` 增量（不新建特性）
> 状态：设计待审阅
> brainstorming 产出，实施走 spec-kit SDD（更新 004 spec/tasks/contracts）

## 1. 背景与目标

004 portal 当前仅 `GET /admin/tasks/{taskId}/export` 按 taskId 导出单个**已完成**任务结果。运维在浏览器 portal 时无法浏览「所有/最近任务」，必须先知道 taskId 才能查。本增量补「异步任务列表查询」能力：列出任务摘要（不含 frames 的轻量视图），支持过滤 / 分页，前端在 `/tasks` 页加列表区，点列表项衔接现有导出流。

### 约束（来自 CLAUDE.md / 宪法）

- 复用 `com.arthas.gateway.task.TaskStore`（001/003 已有，`list()` / `list(TaskState status)` 已存在），**不引入新存储**。
- 前端=展示层，核心逻辑 Java 后端（宪法原则六）。
- 能力开关由 `application.yml` 配置驱动（`@ConditionalOnProperty`），**不在 portal 前端处理**开关逻辑——前端只是端点 404 时被动降级显示。
- TDD 真实环境，零桩（宪法原则七）：Service 单测可用 mock TaskStore；ContractIT 必须真实 Spring 上下文 + JDK HttpClient 赸实 HTTP 调用。

## 2. 决策摘要（brainstorming 结论）

| # | 决策点 | 选择 | 备选（已否） |
|---|--------|------|-------------|
| 1 | 前端形态 | 复用 `/tasks` 页加「最近任务」列表区，点列表项填 taskId 衔接导出 | 独立 `/tasks/list` 路由（功能分散）；列表为主、导出并入展开（去掉按 id 直查） |
| 2 | 列表契约 | 摘要 7 字段（**无 frames**）+ `createdAt` 倒序 + 标准分页 `?page&size` + `total` | 不截断（响应可能大）；上限截断（无 total，前端难翻页） |
| 3 | 过滤维度 | `status` + `tool` + `target` 三维度可选，默认不传=全部 | 仅 status（不够灵活）；不过滤（看失败需手翻） |
| 4 | 能力开关 | 复用 `arthas-gateway.admin.export.enabled`（与 export 共用，yaml 驱动） | 独立 `task-list.enabled`（YAGNI，无"只许看不许导出"需求） |
| 5 | 特性归档 | 004 增量（更新 004 spec/tasks/contracts） | 新建 `005-portal-task-list`（同属 portal，不值得拆） |

## 3. 后端 API 设计

### `GET /admin/tasks` — 列表查询

- **路径参数**：无
- **查询参数（全可选）**：

| 参数 | 类型 | 默认 | 说明 |
|------|------|------|------|
| `status` | enum: `COMPLETED`/`WORKING`/`FAILED`/`CANCELLED` | 不传=全部 | 复用 `TaskStore.list(TaskState)` 重载 |
| `tool` | string | 不传=全部 | 工具名**精确**匹配（如 `watch`、`jvm`），stream filter |
| `target` | string | 不传=全部 | target 名**精确**匹配（如 `debian-demo-business`），stream filter |
| `page` | int ≥ 0 | `0` | 页码（0-based） |
| `size` | int 1..100 | `20` | 每页条数；超 100 截断为 100，<1 取 1 |

- **响应 200**：
```json
{
  "items": [TaskSummaryDto],
  "total": 42,
  "page": 0,
  "size": 20
}
```
  - `total`：**过滤后、分页前**的总数（分页元数据一致性 invariant）
  - `items`：当前页的摘要列表，按 `createdAt` 倒序

- **TaskSummaryDto（7 字段 record，无 frames）**：
  `taskId` / `tool` / `target` / `status` / `createdAt` / `completedAt` / `isError`

- **行为**：
  1. `TaskStore.list()`（或 `list(status)` 当传 status）触发惰性清理 + 取快照
  2. stream `filter(tool)` + `filter(target)`
  3. `sorted(createdAt 倒序)`
  4. `total = count()`
  5. `skip(page*size).limit(size)` → items

- **错误**：列表查询不抛业务异常。空结果 → `{"items":[], "total":0, "page":0, "size":20}`（200，非 404）。`size`/`page` 越界由参数归一化处理（不返 400，clamp 到合法区间）。

- **能力开关**：`@ConditionalOnProperty(name="arthas-gateway.admin.export.enabled", havingValue="true", matchIfMissing=true)`，与 `TaskExportController` 共用 → 关闭时 `GET /admin/tasks` 与 `/{taskId}/export` **都 404**。

## 4. 后端组件（复用 + 最小新增）

| 组件 | 路径 | 职责 | 复用/新增 |
|------|------|------|-----------|
| `TaskSummaryDto` | `admin/task/dto/` | 7 字段 record | **新增** |
| `TaskListService` | `admin/task/` | `list(status, tool, target, page, size)` → 过滤/排序/分页 | **新增** |
| `TaskExportController` | `admin/task/` | 加 `@GetMapping`（无 `{taskId}`）收 query 参数 = 列表端点；与 `/{taskId}/export` 并存 | **复用**（已是 `@RequestMapping("/admin/tasks")` + 同开关，一个 controller 管该路径全部端点） |
| `AdminExceptionHandler` | `admin/` | 无需改（列表不抛新异常） | 不动 |
| `TaskStore` | `task/` | `list()` / `list(status)` 已存在 | 不动 |

**单一职责**：`TaskListService` 只管过滤/排序/分页（纯转换），不碰 HTTP；`TaskExportController` 只管 HTTP 绑定 + 开关；`TaskStore` 只管存储 + TTL。三者边界清晰。

## 5. 前端改造

### `TaskExportView.vue`（`/tasks` 页）

- **上方新增「最近任务」列表区**：
  - 进入页面 `onMounted` 自动调 `listTasks({page:0, size:20})` 查第一页
  - 摘要表：列 = `taskId` / `tool` / `target` / 状态徽标（复用 `HealthBadge` 风格，COMPLETED 绿/FAILED 红/WORKING 黄） / `createdAt`
  - 分页控件：上一页 / 下一页 + 当前页码（`page=0` 时禁用上一页；`items.length < size` 时禁用下一页）
  - status 过滤下拉：选项 `全部` / `COMPLETED` / `WORKING` / `FAILED` / `CANCELLED`，切换重查第一页
- **点击列表项** → 自动填入下方 `taskId` 输入框 + 触发 `query()`（无缝衔接现有导出流，复用 `exportTask` + `DownloadButton`）
- **下方保留**现有"按 taskId 查 + 下载"区不变

### `adminClient.ts`

- 新增类型 `TaskSummaryDto` / `TaskSummaryPage { items, total, page, size }`
- 新增 `listTasks(params: {status?, tool?, target?, page?, size?})` → 同源 `GET /admin/tasks`，返 `TaskSummaryPage`

## 6. 测试策略（TDD 红-绿-重构）

| 测试 | 类型 | 覆盖 |
|------|------|------|
| `TaskListServiceTest` | 单测（mock TaskStore） | status/tool/target 过滤组合；createdAt 倒序；page/size 分页；total = 过滤后总数；size clamp 100 |
| `TaskListContractIT` | @SpringBootTest + JDK HttpClient | HTTP `GET /admin/tasks` 各种参数；分页元数据 `total/page/size`；排序断言；空结果 200 |
| `AdminCapabilitySwitchIT` 扩展 | @SpringBootTest | `export.enabled=false` → `/admin/tasks` 也 404（与 export 同命运） |
| 前端 `TaskExportView.test` | vitest + @vue/test-utils | 列表渲染 / 点项填 taskId / 分页交互 / status 过滤切换 |

**真实性约束**：Service 单测 mock TaskStore（边界已验证的存储，mock 其返回的 `List<GatewayTask>`）；ContractIT **真实 Spring 上下文 + 真实 TaskStore Bean + 真实 HTTP**（put 几条真实 GatewayTask 进 store，再 JDK HttpClient 打 `/admin/tasks`），不 mock 网关内部。

## 7. 文档更新（004 增量）

- `specs/004-portal-backend-management/contracts/admin-api-contract.md` 加 **§2.1 `GET /admin/tasks`**（参数/响应/分页/排序）
- `specs/004-portal-backend-management/contracts/admin-invariants.md` 加：
  - **INV-LIST-1**：列表项 `TaskSummaryDto` **禁含 frames**（摘要纯，frames 仅由 `/{taskId}/export` 提供）
  - **INV-LIST-2**：`total` = 过滤后、分页前的总数（与 `items` 分页独立）
  - **INV-LIST-3**：`items` 按 `createdAt` 倒序（最新在前）
  - **INV-LIST-4**：`export.enabled=false` → `/admin/tasks` 与 `/admin/tasks/{id}/export` 同 404
- `specs/004-portal-backend-management/spec.md` 加 FR-015（列表查询）+ SC-005（列表/过滤/分页/点项导出端到端）
- `specs/004-portal-backend-management/tasks.md` 追加任务块（测试先于实现）
- `quickstart.md` 加场景 E：浏览任务列表 → 过滤 → 点项导出

## 8. 不在本范围（YAGNI）

- **模糊匹配** tool/target（MVP 精确匹配足够）
- **多排序字段**（仅 createdAt 倒序）
- **游标分页**（标准 offset 分页足够 MVP 量级）
- **任务统计聚合**（如按 tool 分组计数，留给后续）
- **WebSocket 实时推送**任务状态变化（刷新即可）
- **独立 `task-list.enabled` 开关**（无此细粒度需求）
