package com.junge.aidepro.ipc

import com.junge.aidepro.hook.config.AppConfig
import com.junge.aidepro.hook.config.HookRule
import kotlinx.serialization.json.Json

/**
 * 配置的 JSON 编解码。
 *
 * 被 Hook 进程通过 Binder 拿到的是 JSON 字符串，自己解析。
 * 这样协议层与配置结构解耦：将来加开关不会影响 AIDL 接口。
 */
internal object ConfigJson {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun encode(config: AppConfig): String =
        runCatching { json.encodeToString(AppConfig.serializer(), config) }
            .getOrDefault("{}")

    fun encodeRules(rules: List<HookRule>): String =
        runCatching { json.encodeToString(kotlinx.serialization.builtins.ListSerializer(HookRule.serializer()), rules) }
            .getOrDefault("[]")

    fun decode(raw: String?): AppConfig? {
        if (raw.isNullOrBlank()) return null
        return runCatching { json.decodeFromString(AppConfig.serializer(), raw) }.getOrNull()
    }

    fun decodeRules(raw: String?): List<HookRule> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(HookRule.serializer()), raw)
        }.getOrDefault(emptyList())
    }
}
