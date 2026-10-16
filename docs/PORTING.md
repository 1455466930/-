# 与原版 APK 的功能对照

对照对象：`com.junge.algorithmAidePro` 2.0.4（versionCode 204）

本工程是**独立重写**，未复用原版任何代码或二进制产物。
原版的 native 二进制（`libcore.so` 抓包引擎、`libfrida.so`）属于第三方产物，未做任何提取或反编译搬运，
本工程用开源方案与自研实现达到等效功能。

## 模块对照

| 原版 | 本工程 | 说明 |
|---|---|---|
| `hook.XpInit`（单类上万行） | `hook.engine.HookEngine` + `ModuleRegistry` | 改为 SPI 插件注册，新增功能不改引擎 |
| `Config.ConfigReader implements SharedPreferences` | `hook.config.ConfigRepository` + `HookConfigCache` | 原版每次读配置都走 system_server IPC 且在 Hook 热路径上；改为本地 DataStore + revision 失效 |
| `Config.ConfigKeys$App`（37 个 String 常量） | `hook.config.HookSwitch`（枚举） | 编译期检查，可遍历，自动生成 UI |
| `bean.LogDataV2`（三模式存三份） | `data.model.LogRecord` | 只存原始字节，渲染模式读取时决定；CRC 去重 |
| `db`（ObjectBox + greenDAO 双 ORM） | `data.db`（Room 单一） | 去掉双 ORM 维护负担，保留类型转换 |
| `ipc.IAlgorithmService` / `ILogManager` / `IPacketCaptureService` | `mcp` + `capture.PacketSink` | 原因：原版三个 AIDL 接口方法命名混乱（`app_*`/`service_*` 混用）；本工程用 Outbox 有界缓冲跨进程投递，宿主不可用时丢弃而不阻塞目标应用 |
| native `libcore.so` 的 MCP server | `mcp.McpHttpServer` + `McpDispatcher` | JDK ServerSocket 自实现，约 200 行可审计；原版放在 C++ 里排查困难 |
| native inline hook 抓包 | `hook.modules.HttpCaptureModule` | Java 层 hook OkHttp / HttpURLConnection / WebView；覆盖面略窄（看不到 native 库发起的请求），但不依赖第三方二进制 |
| 19 个 Activity + Fragment + ViewPager | Navigation Compose + 单 Activity | 状态由 ViewModel 承载，跳转与回退统一管理 |
| `PacketCaptureService`（逻辑/通知/Binder 全塞一起） | `capture.CaptureService`（仅生命周期）+ `PacketSink`（落库） | 职责分离 |

## 保留的对外契约

以下部分与原版保持一致，便于迁移与对接：

- **Xposed 入口**：`assets/xposed_init` → `com.junge.aidepro.hook.XpEntry`
- **MCP 协议**：`initialize` / `tools/list` / `tools/call`，JSON-RPC 2.0
- **MCP 工具名**：与原版 native 层字符串表中的工具名逐一对应
- **游标格式**：`afterId` 为 `v2:<time>:<h|t>:<id>`，`id` 为 `<rowid>` 或 `<rowid>-h2-<n>`
- **端口**：默认 19191，仅绑定 127.0.0.1

## 功能开关对照（37 项）

原版 `ConfigKeys$App` 的 37 个 String 常量全部一一映射到 `HookSwitch` 枚举的 `key` 字段，
取值与原版完全一致，因此原版的配置 JSON 可以直接被本工程解析（字段名不变）。

| 分组 | 项数 | 开关 |
|---|---|---|
| 加密算法 | 4 | digest / mac / cipher / webCrypt |
| 网络与证书 | 1 | justTrustMePlus |
| 系统与环境 | 5 | checkRoot / hiddenXposed / hiddenWifiProxy / hiddenVpn / sign |
| 存储访问 | 4 | assets / fileRead / fileWrite / fileDelete |
| 执行 | 1 | shell |
| 界面交互 | 5 | onClick / dialog / closeDialog / textView / activity |
| WebView | 2 | webViewDebug / webViewLoadUrl |
| 数据库 | 6 | sqliteOpen / insert / delete / update / query / execSQL |
| SharedPreferences | 3 | spOpen / spGet / spPut |
| 媒体与系统 | 6 | camera / log / application / exit / exception / screen |

全局项 11 项（`GlobalSwitch`）同样保持 key 一致。

## 已知差异

1. **抓包覆盖面**：原版 native inline hook 可捕获所有 socket 流量（含 native 库请求）；
   本工程当前只覆盖 Java 网络栈。如需补齐，可在 `HookModule` 层新增 native 实现，
   接口不变。
2. **Frida 集成**：原版内置 `libfrida.so`（6MB）直接注入；本工程提供脚本编辑与日志展示，
   实际注入需依赖设备端已安装的 Frida server。
3. **数据目录迁移**：原版有 `IDataDirectoryMigrationCallback`，本工程暂未实现，
   数据固定存放在应用私有目录（支持备份规则排除）。
4. **会员体系**：原版包含 `codex.*` 系列登录/校验代码，本工程不涉及，全部移除。

## 安全加固（相对原版）

原版的问题与本工程的处置：

| 原版问题 | 本工程处置 |
|---|---|
| `AlgorithmBridgeReceiver` / `ConfigProvider` / `AlgorithmServiceProvider` 全部 `exported=true` 且无权限保护，任意应用可读写配置并拉起服务 | 所有组件 `exported=false`，不暴露对外接口；MCP 服务仅绑 127.0.0.1 并支持 token 校验 |
| `usesCleartextTraffic=true` 全局放开明文 | `network_security_config` 仅对 127.0.0.1 放行，其余强制 TLS |
| 被 Hook 进程同步 Binder 回调写库，宿主未启动时阻塞目标应用 | Outbox 有界缓冲 + 批量投递，队列满丢弃并计数，不阻塞目标应用 |
| 配置读取在 Hook 热路径上跨进程 | 进程内只读缓存 + revision 失效 |
| 自定义 Hook 规则把运行时参数值与配置混存 | `HookRule` 只存规则；返回值替换按目标类型转换，失败则放弃替换而非抛异常 |
