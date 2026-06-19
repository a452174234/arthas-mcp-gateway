# MCP 能力清单（tool / resource / prompt）

> 本文件是网关向调用方（Claude Code）**暴露的工具/资源/提示词定义的单一事实源**。
> 依据宪法原则二（v1.2.0）：定义来源于对 arthas 源码的**如实摘抄（静态拷贝）**，不从各后端动态发现；不做无依据改写。
> 全部字段已逐行核对源码（带 `file:line`），可作为网关 `tools/list` 的**静态填充依据**。
> 路径相对 `reference/arthas/`。

---

## 0. 摘抄规则（先读这一节）

1. **inputSchema 恒定结构**：`{"type":"object","properties":{...},"required":[...],"additionalProperties":false}`（`arthas-mcp-server/.../tool/util/JsonSchemaGenerator.java:41,43,71-80`）。网关生成的 schema 必须保持此结构。
2. **类型映射**（`JsonSchemaGenerator.java:91-127`）：`String→string`、`int/Integer/long/Long→integer`、`double/float→number`、`boolean→boolean`、Java 数组→`array`（按元素类型定 `items`）、其余→`object`。
3. **required 判定**：`@ToolParam.required()` 优先，默认 **true**（`tool/annotation/ToolParam.java:13`）；false 才不进 `required` 数组。
4. **无 enum / default / 约束**：JsonSchemaGenerator **只生成 type**（`JsonSchemaGenerator.java:91-127`）。凡描述里写"可选值 A/B/C"，仅是 **description 文本**，不进 inputSchema。网关若要增强可读性可保留在 description，但**不得**擅自补 enum（属无依据改写）。
5. **`streamable` 不进协议**：仅服务端执行策略（`McpToolUtils.java:42-47` 构造 `McpSchema.Tool` 时无此字段）。网关 `tools/list` 不暴露；要判流式请查 [工具传输分类表](./工具传输分类表.md)。
6. **`taskSupport` 进协议**：挂在 `McpSchema.Tool.execution.taskSupport`（`McpSchema.java:1535/1633`），JSON 值为 `forbidden/optional/required`（`McpSchema.java:1619-1631`）。
7. **参数命名不统一，照实透传**：ClassLoader hash 有三种写法（`classLoaderHash` / `classLoaderHashcode`(dump 独有) / `classLoaderStr`(sc 独有)）；展开层级有 `expandLevel`/`expand`；OGNL 表达式有 `ognlExpression`/`express`/`expression`/`condition`/`searchExpression`。**不要归一化**。
8. **最后一个参数 `ToolContext` 不计入 MCP 参数**（`JsonSchemaGenerator.java:49-51` 跳过无 `@ToolParam` 的参数）。

> 网关实现提示：建议把本清单的每个工具**导出为常量/静态注册表**（name → ToolDefinition），运行时 `tools/list` 直接返回，避免依赖后端。

---

## 1. 工具分类总览

按 `@Tool.taskSupport`（经 `core/.../core/mcp/ArthasMcpServer.java:147-185` 的 `scanAndClassifyTools` 分类）：

| 分类 | taskSupport | 数量 | 工具 |
|---|---|---|---|
| 普通工具 | `forbidden` | **27** | options, stop, version, viewfile, **dashboard**（streamable 但 forbidden）, getstatic, heapdump, jvm, mbean, memory, ognl, perfcounter, sysenv, sysprop, thread, vmoption, vmtool, classloader, dump, jad, mc, redefine, retransform, sc, sm, **profiler** |
| 任务感知工具 | `optional` | **5** | monitor, stack, tt, trace, watch |
| 必须任务 | `required` | **0** | — |

> 关键事实：arthas MCP **无任何 REQUIRED 工具**；5 个 OPTIONAL；dashboard 是"streamable + forbidden"的特例（走流式执行但不能当 task 调用）。

---

## 2. 逐工具定义（31 个）

> 字段说明：`name`/`描述`取自 `@Tool`；`streamable`/`taskSupport` 为注解实际值（未显式声明时注明默认）。参数表 `required` 列以 `@ToolParam.required()` 为准。

### basic1000 组（基础命令）

#### options — OptionsTool
- 源：`core/.../core/mcp/tool/function/basic1000/OptionsTool.java:13`
- @Tool：name=`options`，streamable=**false**(默认)，taskSupport=**forbidden**(默认)
- 描述：Options 诊断工具：查看或修改 Arthas 全局开关选项，对应 Arthas 的 options 命令。（不带参数列出所有选项；只指定 name 看当前值；指定 name 和 value 修改。常用：unsafe/dump/json-format/strict）
- 参数：

| 参数 | 类型 | required | 描述（摘抄） |
|---|---|---|---|
| name | string | false | 选项名称，如：unsafe, dump, json-format, strict 等 |
| value | string | false | 选项值，用于修改选项时指定新值 |

#### stop — StopTool
- 源：`basic1000/StopTool.java:19`
- @Tool：name=`stop`，streamable=**false**，taskSupport=**forbidden**
- 描述：彻底停止 Arthas。停止后不能再调用任何 tool。为确保 MCP client 收到返回结果，本 tool 会先返回、再延迟执行 stop。
- 参数：

| 参数 | 类型 | required | 描述 |
|---|---|---|---|
| delayMs | integer | false | 延迟执行 stop 的毫秒数，默认 1000ms |

#### version — VersionTool
- 源：`basic1000/VersionTool.java:12`
- @Tool：name=`version`，streamable=**false**，taskSupport=**forbidden**
- 描述：Version 诊断工具：查看当前 JVM 内运行的 Arthas 版本，对应 Arthas 的 version 命令。
- 参数：无（inputSchema 为 `{"type":"object","properties":{},"additionalProperties":false}`，无 required）

#### viewfile — ViewFileTool
- 源：`basic1000/ViewFileTool.java:30`
- @Tool：name=`viewfile`，streamable=**false**，taskSupport=**forbidden**
- 描述：查看文件内容（仅允许在配置的目录白名单内查看），支持 cursor/offset 分段读取，避免一次性返回大量内容。默认允许目录：工作目录下 arthas-output、`~/logs/`。配置白名单：环境变量 `ALLOWED_DIRS_ENV=/path/a,/path/b`。首次读取传 path（可带 offset/maxBytes）；继续读取传 cursor。
- 参数：

| 参数 | 类型 | required | 描述 |
|---|---|---|---|
| path | string | false | 文件路径（绝对或相对；相对路径在允许目录下解析）。提供 cursor 时可不传 |
| cursor | string | false | 游标（上一段返回的 nextCursor），用于继续读取。提供时忽略 path/offset |
| offset | integer | false | 起始字节偏移量（默认 0） |
| maxBytes | integer | false | 本次最多读取字节数（默认 8192，最大 65536） |

### jvm300 组（JVM 诊断）

#### dashboard — DashboardTool ⚠ 特例
- 源：`jvm300/DashboardTool.java:19`
- @Tool：name=`dashboard`，streamable=**true**，taskSupport=**forbidden**(默认)
- 描述：Dashboard 诊断工具：实时展示 JVM/应用面板，可利用参数控制诊断次数与间隔。对应 Arthas 的 dashboard 命令。
- 参数：

| 参数 | 类型 | required | 描述 |
|---|---|---|---|
| intervalMs | integer | false | 刷新间隔，单位毫秒，默认 3000ms |
| numberOfExecutions | integer | false | 执行次数限制，默认 3。达到指定次数后自动停止 |

> ⚠ 唯一一个 streamable=true 但 taskSupport=forbidden 的工具：走流式执行，但**不能**作为 task 调用。

#### getstatic — GetStaticTool
- 源：`jvm300/GetStaticTool.java:11`
- @Tool：name=`getstatic`，streamable=**false**，taskSupport=**forbidden**
- 描述：GetStatic 诊断工具：查看类的静态字段值，可指定 ClassLoader，支持在返回结果上执行 OGNL 表达式。对应 Arthas 的 getstatic 命令。
- 参数：

| 参数 | 类型 | required | 描述 |
|---|---|---|---|
| className | string | true | 类名表达式匹配，如 java.lang.String 或 demo.MathGame |
| fieldName | string | true | 静态字段名 |
| classLoaderHash | string | false | ClassLoader 的 hashcode（16 进制） |
| classLoaderClass | string | false | ClassLoader 完整类名，可替代 hashcode |
| ognlExpression | string | false | OGNL 表达式 |

#### heapdump — HeapdumpTool
- 源：`jvm300/HeapdumpTool.java:27`
- @Tool：name=`heapdump`，streamable=**false**，taskSupport=**forbidden**
- 描述：Heapdump 诊断工具：生成 JVM heap dump，支持 --live 选项。对应 Arthas 的 heapdump 命令。
- 参数：

| 参数 | 类型 | required | 描述 |
|---|---|---|---|
| live | boolean | false | 是否只 dump 存活对象（--live） |
| filePath | string | false | 指定输出文件路径，默认当前工作目录下 arthas-output 中时间戳命名的 .hprof |

#### jvm — JvmTool
- 源：`jvm300/JvmTool.java:9`
- @Tool：name=`jvm`，streamable=**false**，taskSupport=**forbidden**
- 描述：Jvm 诊断工具：查看当前 JVM 运行时信息。对应 Arthas 的 jvm 命令。
- 参数：无

#### mbean — MBeanTool
- 源：`jvm300/MBeanTool.java:23`
- @Tool：name=`mbean`，streamable=**false**，taskSupport=**forbidden**
- 描述：MBean 诊断工具：查看或监控 MBean 属性信息，对应 Arthas 的 mbean 命令。
- 参数：

| 参数 | 类型 | required | 描述 |
|---|---|---|---|
| namePattern | string | true | MBean 名称表达式匹配，如 `java.lang:type=GarbageCollector,name=*` |
| attributePattern | string | false | 属性名表达式匹配，支持通配符如 CollectionCount |
| metadata | boolean | false | 是否查看元信息（-m） |
| intervalMs | integer | false | 刷新间隔，单位毫秒，默认 3000ms |
| numberOfExecutions | integer | false | 执行次数限制，默认 1 |
| regex | boolean | false | 开启正则匹配，默认通配符，默认 false |

#### memory — MemoryTool
- 源：`jvm300/MemoryTool.java:9`
- @Tool：name=`memory`，streamable=**false**，taskSupport=**forbidden**
- 描述：Memory 诊断工具：查看 JVM 内存使用情况，对应 Arthas 的 memory 命令。
- 参数：无

#### ognl — OgnlTool
- 源：`jvm300/OgnlTool.java:10`
- @Tool：name=`ognl`，streamable=**false**，taskSupport=**forbidden**
- 描述：OGNL 诊断工具：执行 OGNL 表达式，对应 Arthas 的 ognl 命令。
- 参数：

| 参数 | 类型 | required | 描述 |
|---|---|---|---|
| expression | string | true | OGNL 表达式 |
| classLoaderHash | string | false | ClassLoader 的 hashcode（16 进制） |
| classLoaderClass | string | false | ClassLoader 完整类名，可替代 hashcode |
| expandLevel | integer | false | 结果对象展开层次（-x），默认 1 |

#### perfcounter — PerfCounterTool
- 源：`jvm300/PerfCounterTool.java:10`
- @Tool：name=`perfcounter`，streamable=**false**，taskSupport=**forbidden**
- 描述：PerfCounter 诊断工具：查看 JVM Perf Counter 信息，对应 Arthas 的 perfcounter 命令。
- 参数：

| 参数 | 类型 | required | 描述 |
|---|---|---|---|
| detailed | boolean | false | 是否打印更多详情（-d） |

#### sysenv — SysEnvTool
- 源：`jvm300/SysEnvTool.java:10`
- @Tool：name=`sysenv`，streamable=**false**，taskSupport=**forbidden**
- 描述：SysEnv 诊断工具：查看系统环境变量，对应 Arthas 的 sysenv 命令。
- 参数：

| 参数 | 类型 | required | 描述 |
|---|---|---|---|
| envName | string | false | 环境变量名。空或空字符串则查看所有变量 |

#### sysprop — SysPropTool
- 源：`jvm300/SysPropTool.java:10`
- @Tool：name=`sysprop`，streamable=**false**，taskSupport=**forbidden**
- 描述：SysProp 诊断工具：查看或修改系统属性，对应 Arthas 的 sysprop 命令。
- 参数：

| 参数 | 类型 | required | 描述 |
|---|---|---|---|
| propertyName | string | false | 属性名 |
| propertyValue | string | false | 属性值；指定则修改，否则查看 |

#### thread — ThreadTool
- 源：`jvm300/ThreadTool.java:18`
- @Tool：name=`thread`，streamable=**false**，taskSupport=**forbidden**
- 描述：Thread 诊断工具：查看线程信息及堆栈，对应 Arthas 的 thread 命令。一次性输出结果。
- 参数：

| 参数 | 类型 | required | 描述 |
|---|---|---|---|
| threadId | integer | false | 线程 ID |
| topN | integer | false | 最忙前 N 个线程并打印堆栈（-n） |
| blocking | boolean | false | 是否查找阻塞其他线程的线程（-b） |
| all | boolean | false | 是否显示所有匹配线程（--all） |

#### vmoption — VMOptionTool
- 源：`jvm300/VMOptionTool.java:10`
- @Tool：name=`vmoption`，streamable=**false**，taskSupport=**forbidden**
- 描述：VMOption 诊断工具：查看或更新 JVM VM options，对应 Arthas 的 vmoption 命令。
- 参数：

| 参数 | 类型 | required | 描述 |
|---|---|---|---|
| key | string | false | Name of the VM option. |
| value | string | false | 更新值，仅在更新时使用 |

#### vmtool — VMToolTool
- 源：`jvm300/VMToolTool.java:13`
- @Tool：name=`vmtool`，streamable=**false**，taskSupport=**forbidden**
- 描述：虚拟机工具诊断工具：查询实例、强制 GC、线程中断等，对应 Arthas 的 vmtool 命令。
- 参数：

| 参数 | 类型 | required | 描述 |
|---|---|---|---|
| action | string | true | 操作类型：getInstances/forceGc/interruptThread 等 |
| classLoaderHash | string | false | ClassLoader 的 hashcode（16 进制） |
| classLoaderClass | string | false | ClassLoader 完整类名，可替代 hashcode |
| className | string | false | 类名，全限定（getInstances 时使用） |
| limit | integer | false | 返回实例限制数量（-l），getInstances 时使用，默认 10；≤0 不限制 |
| expandLevel | integer | false | 结果对象展开层次（-x），默认 1 |
| express | string | false | OGNL 表达式，对 getInstances 返回的 instances 执行（--express） |
| threadId | integer | false | 线程 ID（-t），interruptThread 时使用 |

### klass100 组（类与字节码）

#### classloader — ClassLoaderTool
- 源：`klass100/ClassLoaderTool.java:17`
- @Tool：name=`classloader`，streamable=**false**，taskSupport=**forbidden**
- 描述：ClassLoader 诊断工具，可以查看类加载器统计信息、继承树、URLs，以及进行资源查找和类加载操作。搜索类的场景优先使用 sc 工具。
- 参数：

| 参数 | 类型 | required | 描述 |
|---|---|---|---|
| mode | string | false | 显示模式：stats(统计，默认)/instances(实例详情)/tree(继承树)/all-classes(所有类，慎用)/url-stats(URL统计)/url-classes(URL与类关系) |
| classLoaderHash | string | false | ClassLoader 的 hashcode（16 进制） |
| classLoaderClass | string | false | ClassLoader 完整类名，可替代 hashcode |
| resource | string | false | 要查找的资源名称，如 META-INF/MANIFEST.MF |
| loadClass | string | false | 要加载的类名，支持全限定名 |
| details | boolean | false | 详情模式：列出每个 URL/jar 中的类名（-d），仅 mode=url-classes 生效 |
| jar | string | false | 按 jar 包名/URL 关键字过滤，仅 mode=url-classes 生效 |
| classFilter | string | false | 按类名/包名关键字过滤，仅 mode=url-classes 生效 |
| regex | boolean | false | 是否使用正则匹配 jar/class（-E），仅 mode=url-classes 生效 |
| limit | integer | false | 详情模式下每个 URL/jar 最多展示类数量（-n），默认 100，仅 mode=url-classes 生效 |

> 注：`mode` 可选值不进 inputSchema enum，仅 description 文本。

#### dump — DumpClassTool
- 源：`klass100/DumpClassTool.java:14`
- @Tool：name=`dump`，streamable=**false**，taskSupport=**forbidden**
- 描述：将 JVM 中实际运行的 class 字节码 dump 到指定目录，适用于批量下载指定包目录的 class 字节码。
- 参数：

| 参数 | 类型 | required | 描述 |
|---|---|---|---|
| classPattern | string | true | 类名表达式匹配，如 java.lang.String 或 demo.MathGame |
| outputDir | string | false | 指定输出目录，默认 arthas-output |
| **classLoaderHashcode** | string | false | ClassLoader 的 hashcode（16 进制）——注意此工具参数名结尾带 `code` |
| classLoaderClass | string | false | ClassLoader 完整类名，可替代 hashcode |
| includeInnerClasses | boolean | false | 是否包含子类，默认 false |
| limit | integer | false | 限制 dump 的类数量，避免输出过多文件 |

> 注：该工具的 ClassLoader 参数命名为 `classLoaderHashcode`（与多数工具的 `classLoaderHash` 不同），**照实透传**。

#### jad — JadTool
- 源：`klass100/JadTool.java:10`
- @Tool：name=`jad`，streamable=**false**，taskSupport=**forbidden**
- 描述：反编译指定已加载类的源码，将 JVM 中实际运行的 class 的 bytecode 反编译成 java 代码。
- 参数：

| 参数 | 类型 | required | 描述 |
|---|---|---|---|
| classPattern | string | true | 类名表达式匹配 |
| classLoaderHash | string | false | ClassLoader 的 hashcode（16 进制） |
| classLoaderClass | string | false | ClassLoader 完整类名，可替代 hashcode |
| sourceOnly | boolean | false | 反编译时只显示源代码，默认 false |
| noLineNumber | boolean | false | 反编译时不显示行号，默认 false |
| useRegex | boolean | false | 开启正则匹配，默认通配符，默认 false |
| dumpDirectory | string | false | 指定 dump class 文件目录，默认 logback.xml 中配置的 log 目录 |

#### mc — MemoryCompilerTool
- 源：`klass100/MemoryCompilerTool.java:14`
- @Tool：name=`mc`，streamable=**false**，taskSupport=**forbidden**
- 描述：Memory Compiler/内存编译器，编译 .java 文件生成 .class。
- 参数：

| 参数 | 类型 | required | 描述 |
|---|---|---|---|
| javaFilePaths | string | true | 要编译的 .java 文件路径，支持多个文件，用空格分隔 |
| classLoaderHash | string | false | ClassLoader 的 hashcode（16 进制） |
| classLoaderClass | string | false | ClassLoader 完整类名，可替代 hashcode |
| outputDir | string | false | 指定输出目录，默认工作目录下 arthas-output |

#### redefine — RedefineTool
- 源：`klass100/RedefineTool.java:10`
- @Tool：name=`redefine`，streamable=**false**，taskSupport=**forbidden**
- 描述：重新加载类的字节码，允许在 JVM 运行时重新加载已存在类的字节码，实现热更新。
- 参数：

| 参数 | 类型 | required | 描述 |
|---|---|---|---|
| classFilePaths | string | true | 要重新定义的 .class 文件路径，支持多个文件，空格分隔 |
| classLoaderHash | string | false | ClassLoader 的 hashcode（16 进制） |
| classLoaderClass | string | false | 指定执行表达式的 ClassLoader 的 class name，可替代 hashcode |

#### retransform — RetransformTool
- 源：`klass100/RetransformTool.java:10`
- @Tool：name=`retransform`，streamable=**false**，taskSupport=**forbidden**
- 描述：热加载类的字节码，允许对已加载的类进行字节码修改并使其生效。
- 参数：

| 参数 | 类型 | required | 描述 |
|---|---|---|---|
| classFilePaths | string | true | 要操作的 .class 文件路径，支持多个文件，空格分隔 |
| classLoaderHash | string | false | ClassLoader 的 hashcode（16 进制） |
| classLoaderClass | string | false | ClassLoader 完整类名，可替代 hashcode |

#### sc — SearchClassTool
- 源：`klass100/SearchClassTool.java:14`
- @Tool：name=`sc`，streamable=**false**，taskSupport=**forbidden**
- 描述：搜索 JVM 中已加载的类。支持通配符(*)和正则表达式匹配，可查看类的详细信息（类加载器、接口、父类、注解等）和字段信息。
- 参数：

| 参数 | 类型 | required | 描述 |
|---|---|---|---|
| classPattern | string | true | 类名模式，支持全限定名。可使用通配符如 *StringUtils 或 org.apache.commons.lang.*，类名分隔符支持 '.' 或 '/' |
| detail | boolean | false | 是否显示类的详细信息（类加载器、代码来源、接口、父类、注解等）。默认 true |
| field | boolean | false | 是否显示类的所有成员变量（字段）信息。需 detail 为 true 才生效 |
| regex | boolean | false | 是否使用正则匹配类名。默认 false（通配符） |
| classLoaderHash | string | false | 指定 ClassLoader 的 hashcode（16 进制） |
| classLoaderClass | string | false | 指定 ClassLoader 的完整类名，可替代 hashcode |
| **classLoaderStr** | string | false | 指定 ClassLoader 的 toString() 返回值（此工具独有） |
| expand | integer | false | 对象展开层级，用于展示更详细的对象结构。默认 0 |
| limit | integer | false | 最大匹配类数量限制（仅显示详细信息时生效）。默认 100 |

> 注：`classLoaderStr`（传 toString）为此工具独有命名，**照实透传**。

#### sm — SearchMethodTool
- 源：`klass100/SearchMethodTool.java:14`
- @Tool：name=`sm`，streamable=**false**，taskSupport=**forbidden**
- 描述：搜索 JVM 中已加载类的方法。支持通配符(*)和正则表达式匹配，可查看方法的详细信息（返回类型、参数类型、异常类型、注解等）。
- 参数：

| 参数 | 类型 | required | 描述 |
|---|---|---|---|
| classPattern | string | true | 类名模式，支持全限定名。可使用通配符；类名分隔符支持 '.' 或 '/' |
| methodPattern | string | false | 方法名模式。可使用通配符如 get* 或 *Name。不指定时匹配所有方法 |
| detail | boolean | false | 是否显示方法详细信息（返回类型、参数类型、异常类型、注解、类加载器等）。默认 true |
| regex | boolean | false | 是否使用正则匹配类名和方法名。默认 false（通配符） |
| classLoaderHash | string | false | 指定 ClassLoader 的 hashcode（16 进制） |
| classLoaderClass | string | false | 指定 ClassLoader 的完整类名，可替代 hashcode |
| limit | integer | false | 最大匹配类数量限制。默认 100 |

### monitor200 组（监控分析）

#### monitor — MonitorTool （task: optional）
- 源：`monitor200/MonitorTool.java:28`
- @Tool：name=`monitor`，streamable=**true**，taskSupport=**optional**
- 描述：Monitor 方法调用监控工具：实时监控指定类的指定方法的调用情况，包括调用次数、成功次数、失败次数、平均 RT、失败率等统计信息。对应 Arthas 的 monitor 命令。
- 参数：

| 参数 | 类型 | required | 描述 |
|---|---|---|---|
| classPattern | string | true | 类名表达式匹配，支持通配符，如 demo.MathGame |
| methodPattern | string | false | 方法名表达式匹配，支持通配符，如 primeFactors |
| condition | string | false | OGNL 条件表达式，满足条件的调用才被监控，如 params[0]<0 |
| intervalMs | integer | false | 监控统计输出间隔，单位毫秒，默认 3000ms |
| numberOfExecutions | integer | false | 执行次数限制，默认 1 |
| regex | boolean | false | 开启正则匹配，默认通配符，默认 false |
| maxMatch | integer | false | 最大匹配类数量，默认 50 |
| timeout | integer | false | 命令执行超时，单位秒，默认 30 秒 |

#### profiler — ProfilerTool
- 源：`monitor200/ProfilerTool.java:33`
- @Tool：name=`profiler`，streamable=**false**，taskSupport=**forbidden**
- 描述：Async Profiler 诊断工具：对应 Arthas 的 profiler 命令，用于采样 CPU/alloc/lock 等事件并输出 flamegraph/jfr 等格式。常用：start（action=start, event=cpu）、stop（action=stop, format=flamegraph, file=/tmp/r.html）、status/list/actions、execute（action=execute, actionArg="stop,file=/tmp/r.html"）。
- 参数（共 36 个，全列）：

| 参数 | 类型 | required | 描述 |
|---|---|---|---|
| action | string | true | 动作（必填），可选值：start/resume/stop/dump/status/meminfo/list/version/load/execute/dumpCollapsed/dumpFlat/dumpTraces/getSamples/actions |
| actionArg | string | false | 动作参数。action=execute 时必填，示例 "stop,file=/tmp/result.html" |
| event | string | false | 采样事件（--event），如 cpu/alloc/lock/wall，默认 cpu |
| interval | integer | false | 采样间隔 ns（--interval），默认 10000000(10ms) |
| jstackdepth | integer | false | 最大 Java 栈深（--jstackdepth），默认 2048 |
| file | string | false | 输出文件路径（--file）；以 .html/.jfr 结尾可推断 format；可含 %t 占位符 |
| format | string | false | 输出格式（--format）：flat[=N]\|traces[=N]\|collapsed\|flamegraph\|tree\|jfr\|md[=N]（兼容 html） |
| alloc | string | false | alloc 事件采样间隔字节数（--alloc），如 1m/512k/1000 |
| live | boolean | false | 仅对存活对象做 alloc 统计（--live） |
| lock | string | false | lock 事件阈值 ns（--lock），如 10ms/10000000 |
| jfrsync | string | false | 与 profiler 一起启动 JFR（--jfrsync） |
| wall | integer | false | wall clock 采样间隔 ms（--wall），推荐 200 |
| threads | boolean | false | 按线程区分采样（--threads） |
| sched | boolean | false | 按调度策略分组线程（--sched） |
| cstack | string | false | C 栈采样方式（--cstack）：fp\|dwarf\|vm\|vmx\|no |
| simple | boolean | false | 使用简单类名（-s） |
| sig | boolean | false | 打印方法签名（-g） |
| ann | boolean | false | 注解 Java 方法（-a） |
| lib | boolean | false | 前置库名（-l） |
| allUser | boolean | false | 仅包含用户态事件（--all-user） |
| norm | boolean | false | 规范化方法名，移除 lambda 数字后缀（--norm） |
| include | array<string> | false | 仅包含匹配的栈帧（可重复多次），等价 --include 'java/*'。传入数组 |
| exclude | array<string> | false | 排除匹配的栈帧（可重复多次），等价 --exclude '*Unsafe.park*'。传入数组 |
| begin | string | false | 当指定 native 函数执行时自动开始采样（--begin） |
| end | string | false | 当指定 native 函数执行时自动停止采样（--end） |
| ttsp | boolean | false | time-to-safepoint 采样别名开关（--ttsp） |
| title | string | false | FlameGraph 标题（--title） |
| minwidth | string | false | FlameGraph 最小帧宽百分比（--minwidth） |
| reverse | boolean | false | 生成反向 FlameGraph/Call tree（--reverse） |
| total | boolean | false | 统计总量而非样本数（--total） |
| chunksize | string | false | JFR chunk 大小（--chunksize），默认 100MB |
| chunktime | string | false | JFR chunk 时间（--chunktime），默认 1h |
| loop | string | false | 循环采样参数（--loop），如 300s |
| timeout | string | false | 自动停止时间（--timeout），如 300s |
| duration | integer | false | 持续采样秒数（--duration）。到时自动 stop 在后台执行，结果不回传 |
| features | string | false | 启用的特性集合（--features） |
| signal | string | false | 采样信号（--signal） |
| clock | string | false | 时间戳时钟源（--clock）：monotonic 或 tsc |

> 注：`include`/`exclude` 为 `String[]`（映射为 `array<string>`）。`action` 可选值不进 enum，仅 description 文本。

#### stack — StackTool （task: optional）
- 源：`monitor200/StackTool.java:25`
- @Tool：name=`stack`，streamable=**true**，taskSupport=**optional**
- 描述：Stack 调用堆栈跟踪工具：输出当前方法被调用的调用路径，帮助分析方法的调用链路。对应 Arthas 的 stack 命令。
- 参数：

| 参数 | 类型 | required | 描述 |
|---|---|---|---|
| classPattern | string | true | 类名表达式匹配，支持通配符 |
| methodPattern | string | false | 方法名表达式匹配，支持通配符 |
| condition | string | false | OGNL 条件表达式，满足条件才被跟踪，如 params[0]<0 |
| numberOfExecutions | integer | false | 捕获次数限制，默认 1 |
| regex | boolean | false | 开启正则匹配，默认通配符，默认 false |
| timeout | integer | false | 命令执行超时，单位秒，默认 30 秒 |

#### tt — TimeTunnelTool （task: optional）
- 源：`monitor200/TimeTunnelTool.java:19`
- @Tool：name=`tt`，streamable=**true**，taskSupport=**optional**
- 描述：TimeTunnel 时空隧道工具：方法执行数据的时空隧道，记录指定方法每次调用的入参和返回信息，对应 Arthas 的 tt 命令。支持记录、列表、搜索、查看详情、重放、删除等操作。
- 参数：

| 参数 | 类型 | required | 描述 |
|---|---|---|---|
| action | string | true | 操作类型：record/t(记录)、list/l(列表)、search/s(搜索)、info/i(详情)、replay/p(重放)、delete/d(删除)、deleteAll/da(删除所有)，默认 record |
| classPattern | string | false | 类名表达式匹配，支持通配符。record 操作时必需 |
| methodPattern | string | false | 方法名表达式匹配，支持通配符。record 操作时必需 |
| condition | string | false | OGNL 条件表达式，满足条件才被记录 |
| numberOfExecutions | integer | false | 记录次数限制，默认 1（仅 record） |
| regex | boolean | false | 开启正则匹配，默认通配符，默认 false |
| index | integer | false | 指定索引，用于 info/replay/delete 等 |
| searchExpression | string | false | 搜索表达式，用于 search 操作，支持 OGNL |
| maxMatchCount | integer | false | Class 最大匹配数量，默认 50 |
| sizeLimit | integer | false | 输出结果大小上限(字节)。对应 -M/--sizeLimit，默认 10*1024*1024 |
| timeout | integer | false | 命令执行超时，单位秒，默认 30 秒（仅 record） |

#### trace — TraceTool （task: optional）
- 源：`monitor200/TraceTool.java:19`
- @Tool：name=`trace`，streamable=**true**，taskSupport=**optional**
- 描述：Trace 方法内部调用路径跟踪工具：追踪方法内部调用路径，输出每个节点的耗时信息，对应 Arthas 的 trace 命令。
- 参数：

| 参数 | 类型 | required | 描述 |
|---|---|---|---|
| classPattern | string | true | 类名表达式匹配，支持通配符 |
| methodPattern | string | false | 方法名表达式匹配，支持通配符 |
| condition | string | false | OGNL 条件表达式，包括 #cost 耗时过滤，如 '#cost>100' |
| numberOfExecutions | integer | false | 执行次数限制，默认 1 |
| regex | boolean | false | 开启正则匹配，默认通配符，默认 false |
| maxMatchCount | integer | false | 指定 Class 最大匹配数量，默认 50 |
| timeout | integer | false | 命令执行超时，单位秒，默认 30 秒 |

#### watch — WatchTool （task: optional）
- 源：`monitor200/WatchTool.java:22`
- @Tool：name=`watch`，streamable=**true**，taskSupport=**optional**
- 描述：Watch 方法执行观察工具：观察指定方法的调用情况，包括入参、返回值和抛出异常等信息，支持实时流式输出。对应 Arthas 的 watch 命令。
- 参数：

| 参数 | 类型 | required | 描述 |
|---|---|---|---|
| classPattern | string | true | 类名表达式匹配，支持通配符 |
| methodPattern | string | false | 方法名表达式匹配，支持通配符 |
| express | string | false | 观察表达式，默认 {params, target, returnObj}，支持 OGNL |
| condition | string | false | OGNL 条件表达式，满足条件才被观察，如 params[0]<0 |
| beforeMethod | boolean | false | 在方法调用之前观察（-b），默认 false |
| exceptionOnly | boolean | false | 在方法抛出异常后观察（-e），默认 false |
| successOnly | boolean | false | 在方法正常返回后观察（-s），默认 false |
| numberOfExecutions | integer | false | 执行次数限制，默认 1 |
| regex | boolean | false | 开启正则匹配，默认通配符，默认 false |
| maxMatchCount | integer | false | 指定 Class 最大匹配数量，默认 50 |
| expandLevel | integer | false | 指定输出结果的属性遍历深度，默认 1，最大 4 |
| sizeLimit | integer | false | 输出结果大小上限(字节)。对应 -M/--sizeLimit，默认 10*1024*1024 |
| timeout | integer | false | 命令执行超时，单位秒，默认 30 秒 |

---

## 3. resource / prompt 盘点

**结论：arthas MCP 不暴露任何 resource / prompt 实体（仅 tools）。**

### 证据
1. arthas 实际只调用 `tools(...)` 注册工具，**从未**调用 `resources(...)` 或 `prompts(...)` 注册实例：
   - `core/.../core/mcp/ArthasMcpServer.java:228`（Streamable 普通 tools）、`:269`（task tools）、`:298`（Stateless tools）。
2. `buildServerCapabilities`（`ArthasMcpServer.java:355-358`）虽 `.prompts(...)` / `.resources(...)` 设置了 capability **标志位**（`listChanged`/`subscribe`），但只是声明"支持该能力通知"，**不注册任何实体**。
3. MCP 框架的 `McpNettyServer.java:49,51` 持有空的 `resources`/`prompts` 容器；`resources/list`、`prompts/list` 永远返回**空列表**。

### 给网关的处理
- 网关 `tools/list`：照本文件 §2 静态填充。
- 网关 `resources/list`、`prompts/list`：**返回空数组**（与后端行为一致）。
- ServerCapabilities 声明：网关可如实声明 `tools`（+ `tasks`，因有 5 个 OPTIONAL 工具）；`resources`/`prompts` capability 即使声明也应预期列表恒空——网关 MVP 可**不声明** resources/prompts capability，避免误导调用方。

---

## 4. 快速统计

- 工具总数：**31**（basic1000:4 + jvm300:13 + klass100:8 + monitor200:6）。
- 无参工具：**3**（version、jvm、memory）。
- streamable=true：**6**（dashboard + monitor/stack/tt/trace/watch）。
- task-aware（optional）：**5**（monitor/stack/tt/trace/watch）；required：**0**。
- 参数最多：**profiler（36 个）**。
- resource / prompt：**0**。
- 关键特例：**dashboard**（streamable + forbidden）。
