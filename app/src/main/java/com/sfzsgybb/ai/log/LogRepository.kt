package com.sfzsgybb.ai.log

import com.sfzsgybb.ai.data.db.LogRecordDao
import com.sfzsgybb.ai.data.model.LogGroup
import com.sfzsgybb.ai.data.model.LogRecord
import com.sfzsgybb.ai.data.model.ValueMode
import com.sfzsgybb.ai.data.db.toEntity
import com.sfzsgybb.ai.data.db.toModel
import java.util.zip.CRC32

/**
 * 日志仓库。
 *
 * 关键设计：
 *  - payload/result 以原始字节保存，渲染模式（text/hex/base64）在读取时决定，
 *    而不是像原版那样在写入时就把三种形式都算好存三份（三倍存储 + 三倍 IO）。
 *  - CRC32 用于折叠重复日志，在写入前计算一次。
 */
class LogRepository(private val dao: LogRecordDao) {

    suspend fun insert(record: LogRecord): Long =
        dao.insert(record.copy(payloadCrc = crc(record.payload), resultCrc = crc(record.results.firstOrNull() ?: ByteArray(0))).toEntity())

    suspend fun insertAll(records: List<LogRecord>): List<Long> =
        dao.insertAll(records.map {
            it.copy(
                payloadCrc = if (it.payloadCrc != 0L) it.payloadCrc else crc(it.payload),
                resultCrc = if (it.resultCrc != 0L) it.resultCrc else crc(it.results.firstOrNull() ?: ByteArray(0)),
            ).toEntity()
        })

    suspend fun byId(id: Long): LogRecord? = dao.byId(id)?.toModel()

    suspend fun page(packageName: String?, group: Int, afterTime: Long, afterId: Long, limit: Int): List<LogRecord> =
        dao.pageBefore(afterTime, afterId, limit).map { it.toModel() }
            .filter { (packageName == null || it.packageName == packageName) && (group < 0 || it.group == group) }

    suspend fun search(query: String, limit: Int, offset: Int): List<LogRecord> =
        dao.search(query, limit, offset).map { it.toModel() }

    suspend fun count(packageName: String? = null): Long = dao.countByPackage(packageName)

    suspend fun clear(packageName: String? = null): Int {
        return if (packageName == null) {
            val n = dao.count().toInt()
            dao.clear()
            n
        } else {
            val n = dao.countByPackage(packageName).toInt()
            dao.clearByPackage(packageName)
            n
        }
    }

    suspend fun delete(ids: List<Long>) = dao.deleteByIds(ids)

    suspend fun markRead(ids: List<Long>) = dao.markRead(ids)

    suspend fun trim(keep: Int) = dao.trimTo(keep)

    suspend fun groupCounts(packageName: String?): List<Pair<Int, Int>> =
        dao.groupCounts(packageName).map { it.g to it.c }

    /** 按应用包名分组统计（日志页应用卡片用）。 */
    suspend fun packageStats(): List<com.sfzsgybb.ai.data.db.PackageStat> =
        dao.packageCounts()

    suspend fun topLogNames(packageName: String?, limit: Int): List<Pair<String, Int>> =
        dao.topLogNames(packageName, limit).map { it.name to it.c }

    // ---------- 渲染 ----------

    fun render(bytes: ByteArray, mode: ValueMode): String = when (mode) {
        ValueMode.TEXT -> String(bytes, Charsets.UTF_8)
        ValueMode.HEX -> toHex(bytes)
        ValueMode.BASE64 -> android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
    }

    fun brief(bytes: ByteArray, max: Int = 120): String {
        if (bytes.isEmpty()) return ""
        val text = runCatching { String(bytes, Charsets.UTF_8) }.getOrDefault("")
        val printable = text.count { it.code in 32..126 || it.code > 0x7F }
        return if (printable * 100 / maxOf(text.length, 1) >= 85 && text.none { it.code < 9 }) {
            text.replace("\n", "\\n").take(max)
        } else {
            toHex(bytes, max)
        }
    }

    private fun toHex(bytes: ByteArray, max: Int = Int.MAX_VALUE): String {
        val n = minOf(bytes.size, max)
        val sb = StringBuilder(n * 2 + 3)
        for (i in 0 until n) {
            val v = bytes[i].toInt() and 0xFF
            sb.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
        }
        if (bytes.size > n) sb.append("...")
        return sb.toString()
    }

    companion object {
        private const val HEX = "0123456789abcdef"

        fun crc(bytes: ByteArray): Long {
            if (bytes.isEmpty()) return 0L
            val c = CRC32()
            c.update(bytes)
            return c.value
        }

        /**
         * 生成内容摘要。
         * 可打印字符占比高时给文本摘要，否则给 Hex 前缀——
         * 原版对所有内容一律截断成字符串，二进制内容在列表里会显示成乱码。
         */
        fun briefText(bytes: ByteArray, max: Int = 120): String {
            if (bytes.isEmpty()) return ""
            val text = runCatching { String(bytes, Charsets.UTF_8) }.getOrDefault("")
            val sample = text.take(256)
            val printable = sample.count { it.code in 32..126 || it.code > 0x7F }
            val ratio = if (sample.isEmpty()) 0 else printable * 100 / sample.length
            return if (ratio >= 85 && !sample.contains('\u0000')) {
                text.replace("\n", "\\n").replace("\r", "").take(max)
            } else {
                val n = minOf(bytes.size, max / 2)
                buildString(n * 2 + 3) {
                    for (i in 0 until n) {
                        val v = bytes[i].toInt() and 0xFF
                        append(HEX[v ushr 4]).append(HEX[v and 0x0F])
                    }
                    if (bytes.size > n) append("...")
                }
            }
        }
    }
}

/** 日志分组标签，供 MCP 输出可读文本。 */
fun LogGroup_label(g: Int): String = LogGroup.label(g)
