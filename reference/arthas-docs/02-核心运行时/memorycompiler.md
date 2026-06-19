# memorycompiler 模块 · 内存 Java 编译器

> 路径：`memorycompiler/src/main/java/com/taobao/arthas/compiler/`
> 在整体中的位置：**运行时编译底座**。`core` 的 `mc`（内存编译）、`redefine`（热更新）命令依赖它。

## 职责

基于 **JSR-199（`javax.tools.JavaCompiler`，即 JDK 自带的 javac API）**，把**字符串形式的 Java 源码**在运行时编译成 `Class` 或字节码，全程**不落盘**（输出到内存）。这让 arthas 能在目标 JVM 内动态生成类，进而支持：

- `mc /path/Foo.java` —— 编译源码
- 配合 `redefine` —— 编译后替换已加载类（热修复）

## 关键类

### 入口
- **`DynamicCompiler`** (`memorycompiler/.../DynamicCompiler.java`) — **编译器主入口**。
  - `addSource(String className, String source)` — 添加待编译源码（用 `StringSource` 包装）。
  - `addSource(JavaFileObject)` — 直接加 JavaFileObject。
  - **`build()`** — 核心入口：执行编译，返回 `Map<String, Class<?>>`（全限定名 → Class）。
  - `buildByteCodes()` — 同上但返回 `Map<String, byte[]>`（不加载 Class 的场景）。
  - `getErrors()` / `getWarnings()` — 编译诊断（行号 + 消息）。

### JSR-199 适配层
- **`DynamicJavaFileManager`** (`memorycompiler/.../DynamicJavaFileManager.java`) — 自定义 FileManager（继承 `ForwardingJavaFileManager`），**把编译输出拦截到内存**。
  - `getJavaFileForOutput(...)` — 关键：编译器输出 `.class` 时调用，返回 `MemoryByteCode` 而非写文件。
  - `list(...)` — 合并标准 FileManager 与 `PackageInternalsFinder` 的结果，支持从 ClassLoader 找类路径。
- **`StringSource`** (`memorycompiler/.../StringSource.java`) — 包装字符串源码（继承 `SimpleJavaFileObject`，URI 形如 `string:///Foo.java`）。`getCharContent(...)`。
- **`CustomJavaFileObject`** (`memorycompiler/.../CustomJavaFileObject.java`) — 表示来自 URI（如 jar 内 `.class`）的类文件。`getClassName()`、`openInputStream()`。
- **`MemoryByteCode`** (`memorycompiler/.../MemoryByteCode.java`) — 接收编译输出字节码到 `ByteArrayOutputStream`。`openOutputStream()`、`getByteCode()`。

### 类加载与类路径解析
- **`DynamicClassLoader`** (`memorycompiler/.../DynamicClassLoader.java`) — 加载内存中编译产物。
  - `registerCompiledSource(MemoryByteCode)` — 注册编译结果。
  - **`findClass(name)`** — 关键：优先从 `byteCodes` Map 取并 `defineClass`。
  - `getClasses()` / `getByteCodes()`。
- **`PackageInternalsFinder`** (`memorycompiler/.../PackageInternalsFinder.java`) — 扫描某包下所有类文件（用于类路径解析），内部用 `JarFileIndex` 缓存 jar 索引。`find(packageName)`。
- **`ClassUriWrapper`** (`memorycompiler/.../ClassUriWrapper.java`) — 类名 + URI 值对象。
- **`DynamicCompilerException`** (`memorycompiler/.../DynamicCompilerException.java`) — 编译异常，携带诊断列表。`getErrorList()`。

## 编译流程协作

```
源码字符串
  → StringSource → DynamicCompiler.addSource()
  → DynamicJavaFileManager.getJavaFileForOutput()   ← 拦截输出
  → MemoryByteCode.openOutputStream() 接收 .class 字节
  → DynamicClassLoader.registerCompiledSource()
  → DynamicClassLoader.findClass() → defineClass()
  → DynamicCompiler.build() 返回 Map<String, Class<?>>
```

## 与其它模块的关系

- 被 `core/.../command/klass100/MemoryCompilerCommand`（`mc`）和 `RedefineCommand`（`redefine`）调用。
- 间接依赖 [`common`](./common.md) 的 IO/反射工具。

## 定位提示

> "`mc` 命令把 `.java` 编成 `.class` 的代码在哪？" → `DynamicCompiler.build()`。
> "为什么编译不产生临时文件？" → `DynamicJavaFileManager.getJavaFileForOutput()` 返回 `MemoryByteCode`。
