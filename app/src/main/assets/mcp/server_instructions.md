# AlgorithmAidePro MCP 服务

本服务把已捕获的 Hook 日志与抓包数据暴露给 AI 客户端调用。

## 调用约定

1. **抓包前先探状态**：任何抓包类工具调用前，先调 `capture_status` 确认服务可用；
   再用 `capture_sessions` 拿到真实的 `sessionId`。不要臆造 sessionId。
2. **分页游标原样回传**：`list_logs` / `network_list` 返回的 `nextAfterId`，下一次请求
   原样传入 `afterId`。不要自行拼接游标——格式为 `v2:<时间戳>:<h|t>:<行id>`。
3. **id 只能来自上一次返回**：所有 `id` / `recordId` 参数必须来自前一次工具调用的输出。
   HTTP 详情用 `http_info` / `http_body`（传 `id` + `part`）；
   TCP/WebSocket 载荷用 `network_packetBody`（传 `recordId`）。
4. **大响应体分块读**：`http_body` 支持 `offset` + `limit`（上限 256KB），
   通过返回的 `hasMore` / `nextOffset` 续读。
5. **破坏性操作需显式确认**：`clear_logs` / `network_deletes` / `capture_delete_sessions` /
   `capture_stop` 需要传 `confirm: true`。

## 结果渲染

`mode` 参数控制二进制内容的呈现方式：

- `text`：按 UTF-8 解码（默认）
- `hex`：十六进制
- `base64`：Base64

## 搜索语义

`query` 参数在三种表示上同时匹配：原文（ASCII 大小写不敏感）、Hex、Base64。
HTTP 还会匹配 URL 与请求/响应头；TCP/WebSocket 匹配远端地址与收发载荷。
搜索在游标分页之前应用。
