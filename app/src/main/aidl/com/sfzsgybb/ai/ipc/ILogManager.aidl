// 日志管理接口（被 Hook 进程 → 宿主 APP）
//
// 对应原版 com.junge.algorithmAidePro.ipc.ILogManager。
// 这是被 Hook 进程把采集到的数据送回宿主的通道。
//
// 设计要点：
//  - 日志与报文用 Bundle 传（Binder 事务上限 1MB，字段已在客户端侧截断）
//  - 大批量/大体积数据用 openWritePipe 拿 ParcelFileDescriptor 走管道
//  - 所有方法都返回简单类型，避免复杂的序列化

package com.sfzsgybb.ai.ipc;

import android.os.Bundle;
import android.os.ParcelFileDescriptor;

interface ILogManager {

    /** 宿主服务是否存活。 */
    boolean isRunning();

    /** 协议版本，用于兼容性判断。 */
    int getVersion();

    /**
     * 提交一条日志。
     * bundle 字段：
     *   pkg, logName, className, objName, switchKey, group, callStack, ts
     *   payload  : byte[]  （输入载荷）
     *   results  : byte[][]（输出结果，可能多个）
     */
    void writeLog(in Bundle data);

    /**
     * 提交一条 HTTP 记录。
     * bundle 字段：
     *   pkg, method, url, statusCode, durationMs, ts, reqHeaders, respHeaders
     *   requestBody : byte[]
     *   responseBody: byte[]
     */
    void writeHttp(in Bundle data);

    /**
     * 提交一条 TCP / WebSocket 报文。
     * bundle 字段：pkg, protocol, remoteAddress, outbound, ts, payload
     */
    void writePacket(in Bundle data);

    /**
     * 打开一个写入管道。
     * 被 Hook 进程拿到写端，宿主拿读端，用于持续输出大量数据
     * （例如 Frida 日志流、大响应体分片）。
     */
    ParcelFileDescriptor openWritePipe(String packageName, String channel);

    /** 导出日志（供 MCP / UI 用）。 */
    ParcelFileDescriptor exportLogs(String query);
}
