package com.sfzsgybb.ai.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        LogRecordEntity::class,
        HttpRecordEntity::class,
        PacketDataEntity::class,
        CaptureSessionEntity::class,
        ConnectRecordEntity::class,
        StackRecordEntity::class,
    ],
    version = 5,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun logDao(): LogRecordDao
    abstract fun captureDao(): CaptureDao
    abstract fun analysisDao(): AnalysisDao

    companion object {
        const val NAME = "algorithm_aide_pro.db"

        /** v1 → v2：新增分析查询支持（表结构未变，仅对齐版本号）。 */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 本轮未改动表结构。保留此迁移是为了让旧数据库能平滑升级。
            }
        }

        /** v2 → v3：http_record 新增 packageName 列（数据归属到应用）。 */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE http_record ADD COLUMN packageName TEXT NOT NULL DEFAULT ''")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_http_record_packageName ON http_record(packageName)")
            }
        }

        /**
         * v3 → v4：log_record 新增分类字段。
         *
         * 对齐原版日志详情页的字段排列（算法名称 / 密钥 / 参数 / 结果类别），
         * 让详情页能把加密信息分类标出，而不是一堆裸字节。
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE log_record ADD COLUMN algoName TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE log_record ADD COLUMN keyType TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE log_record ADD COLUMN keyData BLOB NOT NULL DEFAULT x''")
                db.execSQL("ALTER TABLE log_record ADD COLUMN paramDesc TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE log_record ADD COLUMN resultKind TEXT NOT NULL DEFAULT ''")
            }
        }

        /**
         * v4 → v5：区分 Hook 抓包与代理抓包的来源。
         *
         * 之前两条链路的数据混在一张表里，无法按模式过滤。
         * 默认值给 'hook' —— 历史数据大多来自 Hook（VPN 是后来才加的功能）。
         */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE http_record ADD COLUMN source TEXT NOT NULL DEFAULT 'hook'")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_http_record_source ON http_record(source)")
                db.execSQL("ALTER TABLE capture_session ADD COLUMN source TEXT NOT NULL DEFAULT 'hook'")
            }
        }
    }
}
