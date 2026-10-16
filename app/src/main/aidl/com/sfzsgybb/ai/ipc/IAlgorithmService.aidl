// 算法服务接口（被 Hook 进程 ←→ 宿主 APP）
//
// 对应原版 com.junge.algorithmAidePro.ipc.IAlgorithmService。
// 被 Hook 进程通过它读取配置、拉取脚本、上报运行状态。
//
// 设计要点：
//  - 所有方法都不抛受检异常之外的东西，失败返回 null/空值，避免拖垮目标应用
//  - 配置以 JSON 字符串传递，由客户端自己解析（版本兼容性好）

package com.sfzsgybb.ai.ipc;

interface IAlgorithmService {

    /** 宿主服务是否存活。 */
    boolean isRunning();

    /** 协议版本。 */
    int getVersion();

    /** 版本名。 */
    String getVersionName();

    /**
     * 取指定应用的配置（JSON 字符串）。
     * 这是被 Hook 进程获取开关状态的主通道。
     */
    String app_getConfig(String packageName);

    /** 取单个配置项。 */
    String app_getConfigItem(String packageName, String key);

    /** 该应用是否启用了 Hook。 */
    boolean app_isSwitch(String packageName);

    /** 上报一条 Hook 命中记录（用于 UI 显示活跃状态）。 */
    void app_addHook(String packageName, String hookJson);

    /** 取 Frida 脚本内容。 */
    String app_getScript(String packageName, String scriptName);

    /** 给某个应用注册的自定义 Hook 规则列表（JSON）。 */
    String app_getHookRules(String packageName);
}
