# 算法助手公益版

Android 逆向分析工具：**Xposed 加密算法日志** + **双通道抓包** + **MCP 服务**。

不需要电脑、不需要插数据线，手机上直接看加密调用的明文、密钥和调用堆栈；抓包覆盖 Java 层和原生层；内置 MCP 服务，让 AI 客户端直接读数据做分析。

> 仅用于自有设备上的应用调试与协议分析。

**交流反馈**：[加入社区](https://docs.qq.com/doc/DUHZkb1hLWmhQQXN5)

---

## 功能

### 加密算法日志

Hook 标准加密类，拿到业务真正用的参数，而不是一堆裸字节：

| Hook 目标 | 能拿到 |
|---|---|
| `MessageDigest` | 算法名、输入明文、摘要结果 |
| `Mac` | 算法名、**密钥**、待计算数据、MAC 结果 |
| `Cipher` | transformation、**密钥（区分公钥/私钥）**、IV/GCM 参数、明文、密文、加密还是解密 |

每条日志带**调用堆栈**（自动过滤系统帧和反射帧），直接定位到业务代码哪一行调的。

详情页按字段分类展示，密钥/内容/结果各自独立切换 `text` / `hex` / `base64`，一键复制或整条导出。

### 双通道抓包

**Hook 抓包** —— 在目标进程内拦 OkHttp / HttpURLConnection / WebView：

- 拿的是**明文，不需要装证书**
- 加密参数（`sign`、`token`）能看到原始值
- 兼容 R8 混淆的 OkHttp（动态探测类名和方法签名）

**代理抓包** —— 路由层接管，覆盖 Hook 抓不到的应用：

- Cronet、Flutter、自研网络栈、原生代码
- 按应用归属：反查连接 uid 得到包名，数据自动分组，不用在一堆请求里翻
- 默认只接管「应用」页里勾选的 App，可选全局

### MCP 服务

51 个工具通过 MCP 协议暴露，AI 客户端连上后可以直接：

- 读日志、查加密调用、看调用堆栈
- 分析抓包数据、提取 API 端点、找敏感信息、分析鉴权逻辑
- 断点拦截、改包重放、生成请求代码
- 列举应用、读数据库、管理 Hook 规则

---

## 前置要求

| 项 | 要求 |
|---|---|
| Android | 7.0+（API 26） |
| Root | **必需**（Magisk / KernelSU / APatch） |
| Xposed | **必需**（LSPosed，Zygisk 版） |
| 证书 | 抓 HTTPS 时需要，二选一：装系统 CA，或开目标应用的「SSL 证书信任」开关 |

---

## 安装

1. 编译或下载 APK 安装
2. 打开 LSPosed → 模块 → 启用「算法助手公益版」
3. 进入**作用域**，勾选要分析的目标应用
4. 强行停止目标应用，重新打开
5. 回到 APP 首页，应该显示「模块已启用」

> **最容易漏的一步是作用域。** 只启用模块不勾作用域，Hook 不会对任何应用生效。

---

## 构建

```bash
./gradlew :app:assembleDebug
```

产物：`app/build/outputs/apk/debug/AlgorithmAidePro-3.0.0-debug.apk`

环境：JDK 17+、Android SDK 36、Gradle 8.11.1（wrapper 已内置）

### Windows 中文用户名注意

用户目录含中文（如 `C:\Users\李大爷\`）时，Gradle 启动测试 worker 会失败：

```
ClassNotFoundException: worker.org.gradle.process.internal.worker.GradleWorkerMain
```

`assembleDebug` 不受影响，只有 `test*` 任务会中招。把 `GRADLE_USER_HOME` 指向纯英文路径即可：

```powershell
$env:GRADLE_USER_HOME = "G:\gradle-home"
./gradlew :app:testDebugUnitTest
```

---

## 测试

```bash
./gradlew :app:testDebugUnitTest
```

覆盖 HTTP 解析与响应体解码 52 个用例（chunked / obs-fold / gzip / deflate / 截断 / 异常路径）。

---

## 使用

### 日志

1. 「应用」页搜索目标应用，打开总开关
2. 按需开启具体 Hook 项（默认全关）
3. 强行停止目标应用，重新打开
4. 回到「日志」页，顶部按应用筛选，点进去看详情

### 抓包

**Hook 抓包**：开目标应用的「HTTP 抓包」开关 → 重启应用 → 抓包页查看

**代理抓包**：抓包页点「启动代理抓包」→ 首次弹系统 VPN 授权框点确定 → 操作目标应用

### MCP

设置 → MCP 服务 → 启动，然后配置客户端：

```json
{
  "mcpServers": {
    "aidepro": {
      "url": "http://127.0.0.1:19191/mcp"
    }
  }
}
```

电脑上使用时先转发端口：

```bash
adb forward tcp:19191 tcp:19191
```

验证：`curl http://127.0.0.1:19191/health`

局域网模式：设置里打开「允许局域网访问」，同一 WiFi 下直接连手机 IP，无需 adb，**不校验令牌**。

---

## 已知限制

| 场景 | 说明 |
|---|---|
| **证书固定（SSL Pinning）** | 谷歌服务、部分银行类应用抓不到，Hook 也拦不住。可以试 `justTrustMePlushSwitch` 开关 |
| **R8 全混淆的 OkHttp** | Hook 抓包靠方法签名动态探测，完全混淆时可能定位失败，改用代理抓包 |
| **无 SNI 的 HTTPS** | 代理抓包靠 ClientHello 的 SNI 判断目标，直连 IP 的请求会失败 |
| **模块状态检测** | 没 root 读不到框架配置时显示「无法确认」——这是检测受限，不代表功能不可用 |

---

## 工程结构

```
app/src/main/java/com/junge/aidepro/
├── AideProApp.kt              Application（Hilt 入口、MCP 自启）
├── MainActivity.kt            单 Activity + Compose Navigation
├── data/
│   ├── model/                 领域模型
│   └── db/                    Room：实体、DAO、转换器、映射
├── hook/
│   ├── XpEntry.kt             Xposed 入口
│   ├── config/                配置体系（49 项开关 + 全局选项）
│   ├── engine/
│   │   ├── HookEngine.kt      引擎：安装时机 + 异常隔离 + 重试
│   │   ├── HookModule.kt      模块 SPI
│   │   ├── IpcLogReporter.kt  跨进程日志上报
│   │   └── CallStackCapture.kt 调用堆栈采集（过滤系统帧）
│   └── modules/               各功能模块
│       ├── CryptoModules.kt   Digest / Mac / Cipher
│       ├── HttpCaptureModule.kt  OkHttp / HttpURLConnection / WebView
│       ├── NetworkModules.kt  SSL 信任 / Root 绕过 / 隐藏
│       ├── StorageModules.kt  SQLite / SP / 文件 / Shell
│       └── UiModules.kt       UI / WebView / 生命周期
├── ipc/
│   ├── AlgorithmServiceHost.kt  Binder 服务实现（uid 白名单）
│   ├── AlgorithmServiceProvider.kt  ContentProvider 握手
│   └── LogIngest.kt           日志落库
├── capture/
│   ├── http/HttpParser.kt     HTTP/1.x 解析（纯 Kotlin，可单测）
│   ├── http/BodyDecoder.kt    gzip/deflate 解压
│   ├── CaptureRepository.kt   会话与流量
│   └── AnalysisRepository.kt  分析查询（域名汇总、端点提取、敏感信息）
├── mitm/
│   ├── MitmProxyServer.kt     代理服务器
│   ├── TlsTerminator.kt       SSLEngine 驱动（Android 上唯一可靠路径）
│   ├── CertificateManager.kt  CA 管理与动态签发
│   ├── X509Signer.kt          反射 sun.security 签发证书
│   ├── DerEncoder.kt          手编码 DER（绕过 Android 裁剪）
│   └── InterceptEngine.kt     断点与重写
├── vpn/
│   ├── CaptureVpnService.kt   VpnService
│   ├── VpnPacketHandler.kt    用户态 TCP/IP 栈
│   ├── ConnectionManager.kt   连接管理与代理决策
│   ├── UidResolver.kt         uid → 包名（应用归属）
│   └── socket/                NIO 通道与读写
├── mcp/
│   ├── McpHttpServer.kt       JDK ServerSocket 实现的 HTTP 传输
│   ├── McpDispatcher.kt       JSON-RPC 分发
│   └── tools/                 51 个工具实现
└── ui/
    ├── nav/Dest.kt            导航目的地
    └── screens/               首页 / 应用 / 日志 / 抓包 / 设置
```

---

## 实现要点

几个踩过坑的地方，留给后来人：

**跨进程日志回传** —— LSPosed 2.1+ 下 `XSharedPreferences` 读不到宿主配置，`/data/local/tmp` 又受 SELinux 限制。改用 `ContentProvider.call()` 握手拿到宿主 `IBinder`，之后走 AIDL 回传，uid 白名单校验。

**HTTPS 解密** —— Android 裁剪了 `sun.security.x509`，`SubjectAlternativeNameExtension` 和 `BasicConstraintsExtension` 连构造函数都没有。只能用 `DerEncoder` 手工编码扩展，再喂给 `CertificateExtensions(DerInputStream)`。TLS 侧用 `SSLEngine` 手动驱动，`SSLSocketFactory.createSocket(Socket,...)` 在 Conscrypt 上握手成功率为 0。

**VPN 不能漏 `protect()`** —— 转发出去的 socket 不绕过 VPN 就会回到 tun 形成环路，表现为应用一直转圈。

**TCP 序列号是无符号 32 位** —— 按有符号 int 解析会得到负数，序列号比较和累加全乱，网络整个不通。

**Hook 抓包必须替换响应体** —— `ResponseBody.bytes()` 会消费掉流，读完不还回去，业务代码拿到空 body，页面白屏。要用 `ResponseBody.create(MediaType, byte[])` 造副本再 `newBuilder().body().build()`。

**模块状态检测** —— `Class.forName("XposedBridge")` 是错的（API 是 `compileOnly`，APK 里根本没这个类）。改成三级：读 LSPosed 配置库（需 root）→ 反射 XposedBridge → 读 `/proc/self/maps`（不需 root），任一级成立即判定已激活。

---

## MCP 工具清单

**日志** `list_logs` `get_log` `search_logs` `clear_logs` `get_log_graph` `get_log_total`

**抓包会话** `capture_start` `capture_stop` `capture_status` `capture_sessions` `capture_delete_sessions`

**网络记录** `network_list` `network_info` `network_packets` `network_packetBody` `network_stacks` `network_deletes` `http_info` `http_body`

**请求分析** `get_request_list` `get_request_detail` `get_request_body` `search_requests` `get_request_stats` `get_domain_summary` `get_cookie_info` `compare_requests`

**安全分析** `analyze_encrypted_content` `find_sensitive_data` `analyze_auth` `extract_api_endpoints`

**流量控制** `replay_request` `generate_code` `add_breakpoint` `list_breakpoints` `remove_breakpoint` `get_pending_intercepts` `release_intercept` `list_rewrite_rules` `add_rewrite_rule` `remove_rewrite_rule`

**脚本** `list_scripts` `get_script_content` `create_or_update_script`

**抓包控制** `vpn_capture` `mitm_proxy`

**应用与其他** `list_apps` `list_databases` `frida_get_log` `frida_clear_log` `host_mcp_instructions`

---

## 交流与反馈

遇到问题、想交流逆向技巧，或者有功能建议，欢迎加入社区：

**https://docs.qq.com/doc/DUHZkb1hLWmhQQXN5**

提 Issue 也可以，但社区里回复更快。

---

## 许可证

见 [LICENSE](LICENSE)。
