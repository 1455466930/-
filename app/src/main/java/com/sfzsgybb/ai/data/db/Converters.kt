package com.sfzsgybb.ai.data.db

import androidx.room.TypeConverter
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Room 类型转换器。
 *
 * 用 kotlinx.serialization 处理复杂列，避免引入额外 ORM
 * （原版 ObjectBox + greenDAO 双 ORM 是主要维护负担）。
 *
 * 注意：ByteArray 是 Room 原生支持类型（BLOB），不需要也不应定义转换器，
 * 否则 KSP 会报 "Multiple methods define the same conversion"。
 * 但 List<ByteArray> 不是原生类型，需要编码成 List<String>(hex) 存储。
 */
class Converters {

    private val json = Json { ignoreUnknownKeys = true }

    private val stringListSerializer = ListSerializer(String.serializer())

    @TypeConverter
    fun fromStringList(value: List<String>?): String =
        if (value.isNullOrEmpty()) "[]" else json.encodeToString(stringListSerializer, value)

    @TypeConverter
    fun toStringList(value: String?): List<String> =
        if (value.isNullOrBlank()) emptyList()
        else runCatching { json.decodeFromString(stringListSerializer, value) }
            .getOrDefault(emptyList())

    @TypeConverter
    fun fromByteArrayList(value: List<ByteArray>?): String =
        if (value.isNullOrEmpty()) "[]"
        else json.encodeToString(stringListSerializer, value.map { it.toHex() })

    @TypeConverter
    fun toByteArrayList(value: String?): List<ByteArray> =
        if (value.isNullOrBlank()) emptyList()
        else runCatching { json.decodeFromString(stringListSerializer, value).map { it.hexToBytes() } }
            .getOrDefault(emptyList())
}

internal fun ByteArray.toHex(): String {
    val sb = StringBuilder(size * 2)
    for (b in this) {
        val v = b.toInt() and 0xFF
        sb.append(HEX_CHARS[v ushr 4]).append(HEX_CHARS[v and 0x0F])
    }
    return sb.toString()
}

internal fun String.hexToBytes(): ByteArray {
    if (length % 2 != 0) return ByteArray(0)
    val out = ByteArray(length / 2)
    for (i in out.indices) {
        val hi = Character.digit(this[i * 2], 16)
        val lo = Character.digit(this[i * 2 + 1], 16)
        if (hi < 0 || lo < 0) return ByteArray(0)
        out[i] = ((hi shl 4) or lo).toByte()
    }
    return out
}

private const val HEX_CHARS = "0123456789abcdef"
