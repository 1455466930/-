package com.sfzsgybb.ai.di

import android.content.Context
import androidx.room.Room
import com.sfzsgybb.ai.capture.CaptureRepository
import com.sfzsgybb.ai.capture.AnalysisRepository
import com.sfzsgybb.ai.data.db.AppDatabase
import com.sfzsgybb.ai.data.db.CaptureDao
import com.sfzsgybb.ai.data.db.LogRecordDao
import com.sfzsgybb.ai.hook.config.ConfigRepository
import com.sfzsgybb.ai.hook.config.GlobalSwitch
import com.sfzsgybb.ai.log.LogRepository
import com.sfzsgybb.ai.mcp.McpDispatcher
import com.sfzsgybb.ai.mcp.McpHttpServer
import com.sfzsgybb.ai.mcp.McpNetwork
import com.sfzsgybb.ai.mcp.McpToolHandler
import com.sfzsgybb.ai.mcp.McpToolSet
import com.sfzsgybb.ai.mcp.tools.AppListProvider
import com.sfzsgybb.ai.mcp.tools.CaptureDeleteSessionsTool
import com.sfzsgybb.ai.mcp.tools.CaptureSessionsTool
import com.sfzsgybb.ai.mcp.tools.CaptureStartStopTool
import com.sfzsgybb.ai.mcp.tools.CaptureStatusTool
import com.sfzsgybb.ai.mcp.tools.ClearLogsTool
import com.sfzsgybb.ai.mcp.tools.DatabaseProvider
import com.sfzsgybb.ai.mcp.tools.FridaLogProvider
import com.sfzsgybb.ai.mcp.tools.FridaLogTool
import com.sfzsgybb.ai.mcp.tools.GetLogGraphTool
import com.sfzsgybb.ai.mcp.tools.GetLogTool
import com.sfzsgybb.ai.mcp.tools.GetLogTotalTool
import com.sfzsgybb.ai.mcp.tools.HostInstructionsTool
import com.sfzsgybb.ai.mcp.tools.HttpBodyTool
import com.sfzsgybb.ai.mcp.tools.HttpInfoTool
import com.sfzsgybb.ai.mcp.tools.InstructionLoader
import com.sfzsgybb.ai.mcp.tools.ListAppsTool
import com.sfzsgybb.ai.mcp.tools.ListDatabasesTool
import com.sfzsgybb.ai.mcp.tools.ListLogsTool
import com.sfzsgybb.ai.mcp.tools.NetworkDeletesTool
import com.sfzsgybb.ai.mcp.tools.NetworkInfoTool
import com.sfzsgybb.ai.mcp.tools.NetworkListTool
import com.sfzsgybb.ai.mcp.tools.NetworkPacketBodyTool
import com.sfzsgybb.ai.mcp.tools.NetworkPacketsTool
import com.sfzsgybb.ai.mcp.tools.NetworkStacksTool
import com.sfzsgybb.ai.mcp.tools.SearchLogsTool
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import com.sfzsgybb.ai.mcp.tools.VpnControlTool
import com.sfzsgybb.ai.mcp.tools.GetRequestListTool
import com.sfzsgybb.ai.mcp.tools.GetRequestDetailTool
import com.sfzsgybb.ai.mcp.tools.GetRequestBodyTool
import com.sfzsgybb.ai.mcp.tools.SearchRequestsTool
import com.sfzsgybb.ai.mcp.tools.GetRequestStatsTool
import com.sfzsgybb.ai.mcp.tools.GetDomainSummaryTool
import com.sfzsgybb.ai.mcp.tools.GetCookieInfoTool
import com.sfzsgybb.ai.mcp.tools.CompareRequestsTool
import com.sfzsgybb.ai.mcp.tools.AnalyzeEncryptedContentTool
import com.sfzsgybb.ai.mcp.tools.FindSensitiveDataTool
import com.sfzsgybb.ai.mcp.tools.AnalyzeAuthTool
import com.sfzsgybb.ai.mcp.tools.ExtractApiEndpointsTool
import com.sfzsgybb.ai.mcp.tools.ReplayRequestTool
import com.sfzsgybb.ai.mcp.tools.GenerateCodeTool
import com.sfzsgybb.ai.mcp.tools.AddBreakpointTool
import com.sfzsgybb.ai.mcp.tools.ListBreakpointsTool
import com.sfzsgybb.ai.mcp.tools.RemoveBreakpointTool
import com.sfzsgybb.ai.mcp.tools.GetPendingInterceptsTool
import com.sfzsgybb.ai.mcp.tools.ReleaseInterceptTool
import com.sfzsgybb.ai.mcp.tools.ListRewriteRulesTool
import com.sfzsgybb.ai.mcp.tools.AddRewriteRuleTool
import com.sfzsgybb.ai.mcp.tools.RemoveRewriteRuleTool
import com.sfzsgybb.ai.mcp.tools.ListScriptsTool
import com.sfzsgybb.ai.mcp.tools.GetScriptContentTool
import com.sfzsgybb.ai.mcp.tools.CreateOrUpdateScriptTool
import com.sfzsgybb.ai.mcp.tools.RemoveScriptTool
import com.sfzsgybb.ai.mcp.tools.ScriptStore
import com.sfzsgybb.ai.mcp.tools.ScriptStoreImpl
import com.sfzsgybb.ai.mcp.tools.FridaInjectTool
import com.sfzsgybb.ai.mcp.tools.FridaInstallTool
import com.sfzsgybb.ai.mcp.tools.FridaStatusTool
import com.sfzsgybb.ai.mcp.tools.FridaStopTool

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
            .addMigrations(
            AppDatabase.MIGRATION_1_2,
            AppDatabase.MIGRATION_2_3,
            AppDatabase.MIGRATION_3_4,
            AppDatabase.MIGRATION_4_5,
        )
            // 仅允许「降级」时破坏性重建；升级走上面的迁移，不会丢数据
            .fallbackToDestructiveMigrationOnDowngrade()
            .build()

    @Provides
    fun provideLogDao(db: AppDatabase): LogRecordDao = db.logDao()

    @Provides
    fun provideCaptureDao(db: AppDatabase): CaptureDao = db.captureDao()

    @Provides
    fun provideAnalysisDao(db: AppDatabase): com.sfzsgybb.ai.data.db.AnalysisDao = db.analysisDao()

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
    fun provideFridaEngine(
        @dagger.hilt.android.qualifiers.ApplicationContext context: Context,
    ): com.sfzsgybb.ai.frida.FridaEngine = com.sfzsgybb.ai.frida.FridaEngine(context)

    @Provides
    @Singleton
    fun provideInstructionLoader(impl: DefaultInstructionLoader): InstructionLoader = impl

    @Provides
    @Singleton
    fun provideAnalysisRepository(dao: com.sfzsgybb.ai.data.db.AnalysisDao): AnalysisRepository =
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
        mitmController: com.sfzsgybb.ai.mitm.MitmController,
        analysis: AnalysisRepository,
        vpnController: com.sfzsgybb.ai.vpn.VpnController,
        scriptStore: ScriptStore,
        fridaEngine: com.sfzsgybb.ai.frida.FridaEngine,
        @dagger.hilt.android.qualifiers.ApplicationContext context: android.content.Context,
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
        VpnControlTool(vpnController, appList),

        // ---------- MITM 代理控制 ----------
        com.sfzsgybb.ai.mcp.tools.MitmControlTool(mitmController),

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
        RemoveScriptTool(scriptStore),

        // ---------- 应用与环境 ----------
        ListAppsTool(appList),
        ListDatabasesTool(dbProvider),
        FridaLogTool(fridaProvider, clear = false),
        FridaLogTool(fridaProvider, clear = true),
        FridaInjectTool(fridaEngine),
        FridaStatusTool(fridaEngine),
        FridaStopTool(fridaEngine),
        FridaInstallTool(context),
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
            initialConfig = McpHttpServer.Config(
                preferredPort = McpNetwork.PREFERRED_PORT,
                bindLocalhostOnly = !lanEnabled,
                authToken = null,
            ),
        )
    }
}
