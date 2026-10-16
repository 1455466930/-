package com.junge.aidepro.di

import android.content.Context
import androidx.room.Room
import com.junge.aidepro.capture.CaptureRepository
import com.junge.aidepro.capture.AnalysisRepository
import com.junge.aidepro.data.db.AppDatabase
import com.junge.aidepro.data.db.CaptureDao
import com.junge.aidepro.data.db.LogRecordDao
import com.junge.aidepro.hook.config.ConfigRepository
import com.junge.aidepro.hook.config.GlobalSwitch
import com.junge.aidepro.log.LogRepository
import com.junge.aidepro.mcp.McpDispatcher
import com.junge.aidepro.mcp.McpHttpServer
import com.junge.aidepro.mcp.McpNetwork
import com.junge.aidepro.mcp.McpToolHandler
import com.junge.aidepro.mcp.McpToolSet
import com.junge.aidepro.mcp.tools.AppListProvider
import com.junge.aidepro.mcp.tools.CaptureDeleteSessionsTool
import com.junge.aidepro.mcp.tools.CaptureSessionsTool
import com.junge.aidepro.mcp.tools.CaptureStartStopTool
import com.junge.aidepro.mcp.tools.CaptureStatusTool
import com.junge.aidepro.mcp.tools.ClearLogsTool
import com.junge.aidepro.mcp.tools.DatabaseProvider
import com.junge.aidepro.mcp.tools.FridaLogProvider
import com.junge.aidepro.mcp.tools.FridaLogTool
import com.junge.aidepro.mcp.tools.GetLogGraphTool
import com.junge.aidepro.mcp.tools.GetLogTool
import com.junge.aidepro.mcp.tools.GetLogTotalTool
import com.junge.aidepro.mcp.tools.HostInstructionsTool
import com.junge.aidepro.mcp.tools.HttpBodyTool
import com.junge.aidepro.mcp.tools.HttpInfoTool
import com.junge.aidepro.mcp.tools.InstructionLoader
import com.junge.aidepro.mcp.tools.ListAppsTool
import com.junge.aidepro.mcp.tools.ListDatabasesTool
import com.junge.aidepro.mcp.tools.ListLogsTool
import com.junge.aidepro.mcp.tools.NetworkDeletesTool
import com.junge.aidepro.mcp.tools.NetworkInfoTool
import com.junge.aidepro.mcp.tools.NetworkListTool
import com.junge.aidepro.mcp.tools.NetworkPacketBodyTool
import com.junge.aidepro.mcp.tools.NetworkPacketsTool
import com.junge.aidepro.mcp.tools.NetworkStacksTool
import com.junge.aidepro.mcp.tools.SearchLogsTool
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import com.junge.aidepro.mcp.tools.VpnControlTool
import com.junge.aidepro.mcp.tools.GetRequestListTool
import com.junge.aidepro.mcp.tools.GetRequestDetailTool
import com.junge.aidepro.mcp.tools.GetRequestBodyTool
import com.junge.aidepro.mcp.tools.SearchRequestsTool
import com.junge.aidepro.mcp.tools.GetRequestStatsTool
import com.junge.aidepro.mcp.tools.GetDomainSummaryTool
import com.junge.aidepro.mcp.tools.GetCookieInfoTool
import com.junge.aidepro.mcp.tools.CompareRequestsTool
import com.junge.aidepro.mcp.tools.AnalyzeEncryptedContentTool
import com.junge.aidepro.mcp.tools.FindSensitiveDataTool
import com.junge.aidepro.mcp.tools.AnalyzeAuthTool
import com.junge.aidepro.mcp.tools.ExtractApiEndpointsTool
import com.junge.aidepro.mcp.tools.ReplayRequestTool
import com.junge.aidepro.mcp.tools.GenerateCodeTool
import com.junge.aidepro.mcp.tools.AddBreakpointTool
import com.junge.aidepro.mcp.tools.ListBreakpointsTool
import com.junge.aidepro.mcp.tools.RemoveBreakpointTool
import com.junge.aidepro.mcp.tools.GetPendingInterceptsTool
import com.junge.aidepro.mcp.tools.ReleaseInterceptTool
import com.junge.aidepro.mcp.tools.ListRewriteRulesTool
import com.junge.aidepro.mcp.tools.AddRewriteRuleTool
import com.junge.aidepro.mcp.tools.RemoveRewriteRuleTool
import com.junge.aidepro.mcp.tools.ListScriptsTool
import com.junge.aidepro.mcp.tools.GetScriptContentTool
import com.junge.aidepro.mcp.tools.CreateOrUpdateScriptTool
import com.junge.aidepro.mcp.tools.ScriptStore
import com.junge.aidepro.mcp.tools.ScriptStoreImpl

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, AppDatabase.NAME)
            // 注册迁移：不加的话 Room 在版本升级时会抛
            // "A migration from 1 to 2 was required but not found"，
            // 导致所有数据库操作失败（表现为大部分 MCP 工具报错）。
            .addMigrations(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3, AppDatabase.MIGRATION_3_4)
            // 仅允许「降级」时破坏性重建；升级走上面的迁移，不会丢数据
            .fallbackToDestructiveMigrationOnDowngrade()
            .build()

    @Provides
    fun provideLogDao(db: AppDatabase): LogRecordDao = db.logDao()

    @Provides
    fun provideCaptureDao(db: AppDatabase): CaptureDao = db.captureDao()

    @Provides
    fun provideAnalysisDao(db: AppDatabase): com.junge.aidepro.data.db.AnalysisDao = db.analysisDao()

    @Provides
    @Singleton
    fun provideLogRepository(dao: LogRecordDao): LogRepository = LogRepository(dao)

    @Provides
    @Singleton
    fun provideCaptureRepository(dao: CaptureDao): CaptureRepository = CaptureRepository(dao)

    @Provides
    @Singleton
    fun provideConfigRepository(@ApplicationContext context: Context): ConfigRepository =
        ConfigRepository(context)

    @Provides
    @Singleton
    fun provideAppListProvider(impl: DeviceAppListProvider): AppListProvider = impl

    @Provides
    @Singleton
    fun provideDatabaseProvider(impl: DeviceDatabaseProvider): DatabaseProvider = impl

    @Provides
    @Singleton
    fun provideFridaLogProvider(impl: InMemoryFridaLogProvider): FridaLogProvider = impl

    @Provides
    @Singleton
    fun provideInstructionLoader(impl: DefaultInstructionLoader): InstructionLoader = impl

    @Provides
    @Singleton
    fun provideAnalysisRepository(dao: com.junge.aidepro.data.db.AnalysisDao): AnalysisRepository =
        AnalysisRepository(dao)

    @Provides
    @Singleton
    fun provideScriptStore(impl: ScriptStoreImpl): ScriptStore = impl

    @Provides
    @Singleton
    fun provideMcpTools(
        logRepo: LogRepository,
        captureRepo: CaptureRepository,
        appList: AppListProvider,
        dbProvider: DatabaseProvider,
        fridaProvider: FridaLogProvider,
        instructions: InstructionLoader,
        mitmController: com.junge.aidepro.mitm.MitmController,
        analysis: AnalysisRepository,
        vpnController: com.junge.aidepro.vpn.VpnController,
        scriptStore: ScriptStore,
    ): McpToolSet = McpToolSet(
        listOf(
        // ---------- 日志（Hook 引擎） ----------
        ListLogsTool(logRepo),
        GetLogTool(logRepo),
        SearchLogsTool(logRepo),
        ClearLogsTool(logRepo),
        GetLogGraphTool(logRepo),
        GetLogTotalTool(logRepo),

        // ---------- VPN 抓包（路由层接管，覆盖不读系统代理的应用） ----------
        VpnControlTool(vpnController),

        // ---------- MITM 代理控制 ----------
        com.junge.aidepro.mcp.tools.MitmControlTool(mitmController),

        // ---------- 抓包会话与流量 ----------
        CaptureStatusTool(captureRepo),
        CaptureSessionsTool(captureRepo),
        CaptureStartStopTool(captureRepo, start = true),
        CaptureStartStopTool(captureRepo, start = false),
        CaptureDeleteSessionsTool(captureRepo),
        NetworkListTool(captureRepo),
        NetworkInfoTool(captureRepo),
        NetworkPacketsTool(captureRepo),
        NetworkPacketBodyTool(captureRepo),
        NetworkStacksTool(captureRepo),
        NetworkDeletesTool(captureRepo),
        HttpInfoTool(captureRepo),
        HttpBodyTool(captureRepo),

        // ---------- 请求查询（移植自 ProxyPin） ----------
        GetRequestListTool(analysis, captureRepo),
        GetRequestDetailTool(analysis),
        GetRequestBodyTool(analysis),
        SearchRequestsTool(analysis),

        // ---------- 统计分析 ----------
        GetRequestStatsTool(analysis),
        GetDomainSummaryTool(analysis),

        // ---------- 安全分析 ----------
        GetCookieInfoTool(analysis),
        CompareRequestsTool(analysis),
        AnalyzeEncryptedContentTool(analysis),
        FindSensitiveDataTool(analysis),
        AnalyzeAuthTool(analysis),
        ExtractApiEndpointsTool(analysis),

        // ---------- 改包与重放 ----------
        ReplayRequestTool(analysis),
        GenerateCodeTool(analysis),
        AddBreakpointTool(),
        ListBreakpointsTool(),
        RemoveBreakpointTool(),
        GetPendingInterceptsTool(),
        ReleaseInterceptTool(),
        ListRewriteRulesTool(),
        AddRewriteRuleTool(),
        RemoveRewriteRuleTool(),

        // ---------- 拦截脚本 ----------
        ListScriptsTool(scriptStore),
        GetScriptContentTool(scriptStore),
        CreateOrUpdateScriptTool(scriptStore),

        // ---------- 应用与环境 ----------
        ListAppsTool(appList),
        ListDatabasesTool(dbProvider),
        FridaLogTool(fridaProvider, clear = false),
        FridaLogTool(fridaProvider, clear = true),
        HostInstructionsTool(instructions),
        )
    )

    @Provides
    @Singleton
    fun provideMcpDispatcher(toolSet: McpToolSet): McpDispatcher = McpDispatcher(toolSet.tools)

    @Provides
    @Singleton
    fun provideMcpServer(dispatcher: McpDispatcher, config: ConfigRepository): McpHttpServer {
        // 从配置读取绑定策略：
        //  - 局域网模式绑 0.0.0.0，方便手机上的 AI 客户端直连
        //  - **不启用 token 鉴权**：本地局域网自用，加 token 只会让
        //    客户端配置变麻烦（每次都要带 ?token=xxx），收益不抵成本。
        //    如果将来要暴露到公网，再把这个开关加回来。
        val lanEnabled = config.getGlobal(GlobalSwitch.MCP_LAN_ENABLED)
        return McpHttpServer(
            dispatcher = dispatcher,
            config = McpHttpServer.Config(
                preferredPort = McpNetwork.PREFERRED_PORT,
                bindLocalhostOnly = !lanEnabled,
                authToken = null,
            ),
        )
    }
}
