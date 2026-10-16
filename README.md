# AlgorithmAidePro 3.0

Xposed 模块 + 抓包工具 + 算法日志分析，内建 MCP 服务供 AI 调用。

## 工程结构

```
app/src/main/java/com/junge/aidepro/
├── AideProApp.kt              Application（Hilt 入口、MCP 自启）
├── MainActivity.kt            单 Activity + Compose Navigation
├── data/
│   ├── model/                 领域模型（LogRecord / HttpRecord / PacketData ...）
│   └── db/                    Room：实体、DAO、转换器、映射
├── hook/
│   ├── XpEntry.kt             Xposed 入口（IXposedHookLoadPackage）
│   ├── config/                配置体系
│   │   ├── HookSwitch.kt      37 项功能开关 + 全局开关 + HookRule
│   │   ├── ConfigRepository.kt DataStore 配置仓库（含 revision 版本号）
│   │   └── HookConfigCache.kt 被 Hook 进程内的只读缓存
│   ├── engine/
│   │   ├── HookEngine.kt      引擎：时机控制 + 异常隔离
│   │   ├── HookModule.kt      模块 SPI 接口
│   │   ├── ModuleRegistry.kt  模块注册表
│   │   ├── CustomHookInstaller.kt  用户自定义规则
│   │   └── IpcLogReporter.kt  日志上报 + Outbox 有界缓冲
│   └── modules/               各功能模块实现
│       ├── CryptoModules.kt   Digest / Mac / Cipher
│       ├── StorageModules.kt  SQLite / SharedPreferences / File / Assets / Shell
│       ├── NetworkModules.kt  SSL 信任 / Root 绕过 / 隐藏 Xposed·代理·VPN
│       ├── HttpCaptureModule.kt  OkHttp / HttpURLConnection / WebView 抓包
│       └── UiModules.kt       UI 交互 / WebView / Log / 生命周期 / 系统 API
├── capture/
│   ├── http/HttpParser.kt     HTTP/1.x 解析（纯 Kotlin，可单测）
│   ├── http/BodyDecoder.kt    gzip/deflate 解压 + 内容类型识别
│   ├── CaptureRepository.kt   会话与流量数据
│   ├── CaptureService.kt      抓包前台服务
│   └── PacketSink.kt          被 Hook 数据落库投递器
├── mcp/
│   ├── McpProtocol.kt         MCP/JSON-RPC 数据结构与 Schema 辅助
│   ├── McpDispatcher.kt       方法分发（initialize / tools/*）
│   ├── McpHttpServer.kt       JDK ServerSocket 实现的 HTTP 传输
│   └── tools/McpTools.kt      24 个 MCP 工具实现
└── ui/                        Compose 界面
    ├── theme/                 主题与配色
    ├── components/            通用组件
    ├── nav/Dest.kt            导航目的地
    └── screens/               各页面与 ViewModel
```

## 构建

```bash
./gradlew :app:assembleDebug
```

产物：`app/build/outputs/apk/debug/AlgorithmAidePro-3.0.0-debug.apk`

环境要求：JDK 17+、Android SDK 34+、Gradle 8.11.1（wrapper 已内置离线发行版）。

### Windows 中文用户名的重要提示

若用户目录含中文（如 `C:\Users\李大爷\`），Gradle 8.11.1 启动测试 worker 时会失败：

```
ClassNotFoundException: worker.org.gradle.process.internal.worker.GradleWorkerMain
```

原因是 Gradle 把 worker 的 classpath 写进 `@C:\Users\<中文>\...\gradle-worker-classpath*.txt`
参数文件，JVM 读取时路径解析出错。**`assembleDebug` 不受影响，只有 `test*` 任务会中招。**

规避方式：把 `GRADLE_USER_HOME` 指向纯英文路径即可（缓存可用 junction 复用，无需重新下载）：

```powershell
cmd /c mklink /J G:\gradle-home\caches  "$env:USERPROFILE\.gradle\caches"
cmd /c mklink /J G:\gradle-home\wrapper "$env:USERPROFILE\.gradle\wrapper"
$env:GRADLE_USER_HOME = "G:\gradle-home"
./gradlew :app:testDebugUnitTest
```

## 测试

```bash
./gradlew :app:testDebugUnitTest
```

当前覆盖：HTTP 解析与响应体解码 52 个用例（含 chunked / obs-fold / gzip / deflate / 截断 / 异常路径）。

## 启动 MCP 服务

1. 应用内「设置 → MCP 服务」打开开关并启动
2. 客户端配置：

```json
{
  "mcpServers": {
    "algorithm-aide-pro": {
      "url": "http://127.0.0.1:19191/mcp"
    }
  }
}
```

3. 验证：`curl http://127.0.0.1:19191/health`

## MCP 工具清单

**日志**：list_logs / get_log / search_logs / clear_logs / get_log_graph / get_log_total

**抓包**：capture_start / capture_stop / capture_status / capture_sessions / capture_delete_sessions

**流量**：network_list / network_info / network_packets / network_packetBody / network_stacks / network_deletes / http_info / http_body

**应用**：list_apps / list_databases / frida_get_log / frida_clear_log / host_mcp_instructions

## 使用方式

1. 在 Xposed 管理器（LSPosed / EdXposed）中激活本模块
2. 打开 APP，「应用」页找到目标应用，打开开关
3. 点进应用详情，按需开启具体 Hook 项（默认全关）
4. 重启目标应用，回到 APP 的「日志」页查看捕获结果
5. 抓包：抓包页开始 → 目标应用产生请求 → 会话详情查看

## 与原版的关系

本工程是独立实现，未复用原版的任何代码或二进制产物。
功能对照见 `docs/PORTING.md`。
