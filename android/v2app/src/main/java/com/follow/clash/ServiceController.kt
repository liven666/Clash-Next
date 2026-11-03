package com.follow.clash

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.system.Os
import com.follow.clash.common.AccessControlMode
import com.follow.clash.service.IAckInterface
import com.follow.clash.service.ICallbackInterface
import com.follow.clash.service.IEventInterface
import com.follow.clash.service.IRemoteInterface
import com.follow.clash.service.IResultInterface
import com.follow.clash.service.IVoidInterface
import com.follow.clash.service.RemoteService
import com.follow.clash.service.models.AccessControlProps
import com.follow.clash.service.models.NotificationParams
import com.follow.clash.service.models.VpnOptions
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

object ServiceController {
    interface Listener {
        fun onStateChanged(state: RunState, message: String) = Unit
        fun onTraffic(up: Long, down: Long) = Unit
        fun onCoreEvent(event: JSONObject) = Unit
    }

    private const val DEFAULT_TEST_URL = "https://www.gstatic.com/generate_204"
    private const val MAX_PROFILE_BYTES = 16 * 1024 * 1024
    private const val EXTRA_REQUEST_VPN = "request_vpn"
    private const val WIDGET_TICK_MS = 10_000L
    private const val WIDGET_NODE_REFRESH_MS = 30_000L
    private const val WIDGET_IP_REFRESH_MS = 15 * 60_000L
    private const val WIDGET_IP_RETRY_MS = 60_000L
    private const val WIDGET_RENDER_THROTTLE_MS = 5_000L
    private const val PROXY_CATALOG_WARMUP_MS = 15_000L
    private val PROXY_CATALOG_WARMUP_DELAYS = longArrayOf(2_000L, 5_000L, 10_000L)

    private lateinit var app: Application
    private val mainHandler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val widgetWorker = Executors.newSingleThreadExecutor()
    private val listeners = CopyOnWriteArrayList<Listener>()
    private val pending = CopyOnWriteArrayList<(IRemoteInterface) -> Unit>()

    @Volatile
    private var lastNodeRefreshTime = 0L

    @Volatile
    private var lastExitIpAttemptTime = 0L

    @Volatile
    private var lastWidgetRenderTime = 0L

    @Volatile
    private var exitIpRefreshRunning = false

    @Volatile
    private var lastProxyCatalogCacheTime = 0L

    @Volatile
    private var profileRevision = 0L

    @Volatile
    private var proxyCatalogWarmupUntil = 0L

    private val widgetTicker = object : Runnable {
        override fun run() {
            if (AppState.runState != RunState.RUNNING) return
            updateTraffic()
            refreshWidgetData()
            mainHandler.postDelayed(this, WIDGET_TICK_MS)
        }
    }

    @Volatile
    private var remote: IRemoteInterface? = null

    @Volatile
    private var binding = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            DiagnosticLog.record("SERVICE", "remote service connected")
            remote = IRemoteInterface.Stub.asInterface(service)
            binding = false
            val current = remote ?: return
            installEventListener(current)
            pending.toList().also { pending.clear() }.forEach { it(current) }
            syncState()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            DiagnosticLog.record("SERVICE", "remote service disconnected")
            remote = null
            binding = false
            updateState(RunState.ERROR, "后台服务已断开")
        }
    }

    fun init(application: Application) {
        if (::app.isInitialized) return
        app = application
        AppState.init(application)
        GeoResourceManager.init(application)
        GeoResourceManager.prepareBundled()
        ensureDefaultConfig()
        AppState.mode = ProfileCompatibility.modeFrom(configFile().readBytes()) ?: "rule"
        DiagnosticLog.record("APP", "service controller initialized")
        bind()
    }

    fun addListener(listener: Listener) {
        listeners += listener
        listener.onStateChanged(AppState.runState, AppState.lastError)
    }

    fun removeListener(listener: Listener) {
        listeners -= listener
    }

    fun configFile(): File = File(app.filesDir, "config.yaml")

    private fun proxyCatalogCacheFile(): File = File(app.filesDir, "proxy_catalog.json")

    fun geoDirectory(): File = app.filesDir

    fun hasUnsupportedClientProfile(): Boolean = runCatching {
        configFile().isFile &&
            ProfileCompatibility.looksLikeUnsupportedClient(configFile().readBytes())
    }.getOrDefault(false)

    fun refreshWidgetData(forceExitIp: Boolean = false) {
        if (AppState.runState != RunState.RUNNING) {
            ProxyWidgetProvider.updateAll(app, AppState.runState)
            return
        }
        val now = System.currentTimeMillis()
        if (now - lastNodeRefreshTime >= WIDGET_NODE_REFRESH_MS) {
            lastNodeRefreshTime = now
            getProxies {}
        }
        val hasExitIp = AppState.widgetExitRefreshTime > 0L
        val retryInterval = if (hasExitIp) WIDGET_IP_REFRESH_MS else WIDGET_IP_RETRY_MS
        if (forceExitIp || now - lastExitIpAttemptTime >= retryInterval) {
            refreshExitIp(now)
        }
    }

    fun requestToggle() {
        when (AppState.runState) {
            RunState.RUNNING -> stop()
            RunState.STARTING -> Unit
            RunState.STOPPED, RunState.ERROR -> requestStartWithPermission()
        }
    }

    fun requestStartWithPermission() {
        if (VpnService.prepare(app) == null) {
            startAfterPermission()
            return
        }
        val intent = Intent(app, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(EXTRA_REQUEST_VPN, true)
        }
        app.startActivity(intent)
    }

    fun shouldRequestVpn(intent: Intent?): Boolean =
        intent?.getBooleanExtra(EXTRA_REQUEST_VPN, false) == true

    fun startAfterPermission() {
        if (!configFile().isFile) {
            updateState(RunState.ERROR, "请先导入订阅或配置文件")
            return
        }
        DiagnosticLog.record(
            "VPN",
            "start requested; profile=${AppState.profileName}; " +
                "source=${DiagnosticSanitizer.sourceHost(AppState.profileUrl)}; " +
                "mode=${AppState.mode}; savedGroups=${AppState.selectedMap.size}",
        )
        updateState(RunState.STARTING, "正在启动")
        withRemote { service ->
            worker.execute {
                runCatching {
                    GeoResourceManager.ensureInstalled()
                    prepareRuntimeConfig()
                    DiagnosticLog.record(
                        "GEO",
                        "bundled resources ready ${GeoResourceManager.existingCount()}/${GeoResourceManager.resources.size}",
                    )
                    service.updateNotificationParams(
                        NotificationParams(
                            title = AppState.profileName,
                            stopText = "停止",
                            onlyStatisticsProxy = false,
                        ),
                    )
                    val init = JSONObject()
                        .put("home-dir", app.filesDir.absolutePath)
                        .put("version", Build.VERSION.SDK_INT)
                    val setup = JSONObject()
                        .put("selected-map", JSONObject(AppState.selectedMap))
                        .put("test-url", DEFAULT_TEST_URL)
                    service.quickSetup(
                        init.toString(),
                        setup.toString(),
                        chunkCallback { message ->
                            if (message.isNotBlank()) {
                                updateState(RunState.ERROR, message)
                            }
                        },
                        object : IVoidInterface.Stub() {
                            override fun invoke() {
                                startRemoteService(service)
                            }
                        },
                    )
                }.onFailure {
                    DiagnosticLog.record("VPN/ERROR", it.stackTraceToString())
                    updateState(RunState.ERROR, it.message ?: "启动失败")
                }
            }
        }
    }

    private fun startRemoteService(service: IRemoteInterface) {
        val options = VpnOptions(
            enable = true,
            port = 7890,
            ipv6 = false,
            dnsHijacking = false,
            accessControlProps = AccessControlProps(
                enable = false,
                mode = AccessControlMode.ACCEPT_SELECTED,
                acceptList = emptyList(),
                rejectList = emptyList(),
            ),
            allowBypass = true,
            systemProxy = false,
            bypassDomain = emptyList(),
            stack = "mixed",
            routeAddress = emptyList(),
        )
        service.startService(options, AppState.runTime, object : IResultInterface.Stub() {
            override fun onResult(time: Long) {
                AppState.runTime = time
                DiagnosticLog.record("VPN", "remote service started; runtime=$time")
                updateState(RunState.RUNNING, "")
                invoke("startListener", JSONObject.NULL) {}
                invoke("startLog", JSONObject.NULL) {
                    DiagnosticLog.record("CORE", "log stream started")
                }
            }
        })
    }

    fun stop() {
        DiagnosticLog.record("VPN", "stop requested")
        updateState(RunState.STARTING, "正在停止")
        withRemote { service ->
            worker.execute {
                runCatching {
                    service.stopService(object : IResultInterface.Stub() {
                        override fun onResult(time: Long) {
                            AppState.runTime = 0L
                            DiagnosticLog.record("VPN", "remote service stopped")
                            updateState(RunState.STOPPED, "")
                        }
                    })
                }.onFailure {
                    DiagnosticLog.record("VPN/ERROR", it.stackTraceToString())
                    updateState(RunState.ERROR, it.message ?: "停止失败")
                }
            }
        }
    }

    fun syncState() {
        withRemote { service ->
            worker.execute {
                runCatching { service.runTime }
                    .onSuccess { time ->
                        DiagnosticLog.record("VPN", "state synchronized; runtime=$time")
                        AppState.runTime = time
                        updateState(
                            if (time > 0L) RunState.RUNNING else RunState.STOPPED,
                            "",
                        )
                        if (time > 0L) {
                            applyCoreMode(AppState.mode) { message ->
                                if (message.isNotBlank()) {
                                    DiagnosticLog.record(
                                        "CONFIG/ERROR",
                                        "running core mode sync failed: $message",
                                    )
                                }
                            }
                            invoke("startLog", JSONObject.NULL) {}
                        }
                    }
                    .onFailure {
                        DiagnosticLog.record("SERVICE/ERROR", it.stackTraceToString())
                        updateState(RunState.ERROR, it.message ?: "无法读取服务状态")
                    }
            }
        }
    }

    fun updateTraffic() {
        if (AppState.runState != RunState.RUNNING) {
            notifyTraffic(0, 0)
            return
        }
        invoke("getTraffic", false) { result ->
            val data = result?.opt("data") as? String ?: return@invoke
            val traffic = runCatching { JSONObject(data) }.getOrNull() ?: return@invoke
            notifyTraffic(traffic.optLong("up"), traffic.optLong("down"))
        }
    }

    fun updateMode(mode: String, callback: (String) -> Unit = {}) {
        if (mode !in setOf("rule", "global", "direct")) {
            callback("不支持的代理模式")
            return
        }
        if (AppState.runState == RunState.STARTING) {
            callback("代理状态切换中，请稍后再试")
            return
        }
        if (AppState.runState != RunState.RUNNING) {
            persistMode(mode, callback)
            return
        }
        val previousMode = AppState.mode
        applyCoreMode(mode) { message ->
            if (message.isNotBlank()) {
                callback(message)
                return@applyCoreMode
            }
            persistMode(mode) { persistMessage ->
                if (persistMessage.isBlank()) {
                    lastNodeRefreshTime = 0L
                    refreshWidgetData()
                    callback("")
                    return@persistMode
                }
                applyCoreMode(previousMode) { rollbackMessage ->
                    if (rollbackMessage.isBlank()) {
                        DiagnosticLog.record(
                            "CONFIG/ERROR",
                            "mode persistence failed; core restored to $previousMode",
                        )
                        callback("保存代理模式失败，已恢复原模式")
                    } else {
                        DiagnosticLog.record(
                            "CONFIG/ERROR",
                            "mode persistence and rollback failed; rollback=$rollbackMessage",
                        )
                        stop()
                        callback("模式保存及恢复失败，代理已停止，请重新启动")
                    }
                }
            }
        }
    }

    fun getProxies(callback: (JSONObject?) -> Unit) {
        val requestedRevision = profileRevision
        invoke("getProxies", JSONObject.NULL) { result ->
            val data = result
                ?.takeIf { it.optInt("code", -1) == 0 }
                ?.optJSONObject("data")
                ?.takeIf { requestedRevision == profileRevision }
            data?.let {
                updateWidgetNodeFromProxies(it)
                cacheProxyCatalog(it, requestedRevision)
            }
            callback(data)
        }
    }

    internal fun getProxyCatalog(callback: (ProxyCatalog?) -> Unit) {
        if (AppState.runState == RunState.RUNNING) {
            getProxies { data ->
                if (data != null) {
                    callback(ProfileNodeCatalog.fromRuntime(data, AppState.selectedMap))
                } else {
                    getOfflineProxyCatalog(callback)
                }
            }
            return
        }
        getOfflineProxyCatalog(callback)
    }

    private fun getOfflineProxyCatalog(callback: (ProxyCatalog?) -> Unit) {
        val requestedRevision = profileRevision
        worker.execute {
            val bytes = runCatching { configFile().readBytes() }.getOrNull()
            val cached = bytes?.let {
                ProfileNodeCatalog.load(proxyCatalogCacheFile(), it)
            }
            mainHandler.post {
                invoke("getConfig", configFile().absolutePath) { result ->
                    if (requestedRevision != profileRevision) {
                        getProxyCatalog(callback)
                        return@invoke
                    }
                    val rawConfig = result
                        ?.takeIf { it.optInt("code", -1) == 0 }
                        ?.optJSONObject("data")
                    val catalog = rawConfig?.let {
                        ProfileNodeCatalog.fromRawConfig(
                            data = it,
                            selectedMap = AppState.selectedMap,
                            cached = cached,
                        )
                    } ?: cached
                    if (AppState.runState != RunState.RUNNING) {
                        catalog?.let(::discardInvalidSelections)
                    }
                    callback(catalog)
                }
            }
        }
    }

    fun selectProxy(group: String, proxy: String, callback: (String) -> Unit) {
        when (AppState.runState) {
            RunState.RUNNING -> changeProxy(group, proxy, callback)
            RunState.STARTING -> callback("代理状态切换中，请稍后再试")
            RunState.STOPPED, RunState.ERROR -> {
                AppState.rememberSelection(group, proxy)
                AppState.widgetNodeFlag = WidgetInfo.flagFromNodeName(proxy)
                ProxyWidgetProvider.updateAll(app, AppState.runState)
                DiagnosticLog.record(
                    "PROXY",
                    "offline selection saved; group=$group; node=$proxy",
                )
                callback("")
            }
        }
    }

    fun changeProxy(group: String, proxy: String, callback: (String) -> Unit) {
        val requestedRevision = profileRevision
        val params = JSONObject()
            .put("group-name", group)
            .put("proxy-name", proxy)
            .toString()
        invoke("changeProxy", params) { result ->
            if (requestedRevision != profileRevision) {
                DiagnosticLog.record(
                    "PROXY",
                    "stale selection ignored after profile change; group=$group",
                )
                callback("订阅已更新，请重新选择节点")
                return@invoke
            }
            val message = actionError(result, "切换节点失败")
            if (message.isBlank()) {
                AppState.rememberSelection(group, proxy)
                AppState.widgetNode = proxy
                AppState.widgetNodeFlag = WidgetInfo.flagFromNodeName(proxy)
                ProxyWidgetProvider.updateAll(app, AppState.runState)
                lastNodeRefreshTime = 0L
                lastProxyCatalogCacheTime = 0L
                mainHandler.postDelayed({ refreshWidgetData(forceExitIp = true) }, 1_000L)
                DiagnosticLog.record("PROXY", "selection changed; group=$group; node=$proxy")
            } else {
                DiagnosticLog.record("PROXY/ERROR", "selection failed; group=$group; message=$message")
            }
            callback(message)
        }
    }

    fun testDelay(proxy: String, testUrl: String?, callback: (Int) -> Unit) {
        val params = JSONObject()
            .put("proxy-name", proxy)
            .put("test-url", testUrl ?: DEFAULT_TEST_URL)
            .put("timeout", 5000)
            .toString()
        invoke("asyncTestDelay", params) { result ->
            val data = result?.optString("data").orEmpty()
            callback(runCatching { JSONObject(data).optInt("value", -1) }.getOrDefault(-1))
        }
    }

    fun getConnections(callback: (JSONArray) -> Unit) {
        invoke("getConnections", JSONObject.NULL) { result ->
            val raw = result?.optString("data").orEmpty()
            val connections = runCatching {
                JSONObject(raw).optJSONArray("connections") ?: JSONArray()
            }.getOrDefault(JSONArray())
            callback(connections)
        }
    }

    fun importConfig(
        bytes: ByteArray,
        label: String,
        sourceUrl: String = "",
        callback: (Boolean, String) -> Unit = { _, _ -> },
    ) {
        profileMutationBlockedMessage()?.let { message ->
            mainHandler.post { callback(false, message) }
            return
        }
        worker.execute {
            runCatching {
                profileMutationBlockedMessage()?.let(::error)
                writeConfig(bytes, label, sourceUrl)
            }.onSuccess {
                mainHandler.post { callback(true, "配置已导入") }
            }.onFailure {
                DiagnosticLog.record("PROFILE/ERROR", it.stackTraceToString())
                mainHandler.post { callback(false, it.message ?: "导入失败") }
            }
        }
    }

    fun downloadProfile(url: String, callback: (Boolean, String) -> Unit) {
        profileMutationBlockedMessage()?.let { message ->
            mainHandler.post { callback(false, message) }
            return
        }
        worker.execute {
            runCatching {
                var acceptedBytes: ByteArray? = null
                var acceptedUserAgent = ProfileCompatibility.DEFAULT_SUBSCRIPTION_USER_AGENT
                for (userAgent in ProfileCompatibility.subscriptionUserAgents) {
                    val downloaded = downloadProfileBytes(url, userAgent)
                    if (!ProfileCompatibility.looksLikeUnsupportedClient(downloaded)) {
                        acceptedBytes = downloaded
                        acceptedUserAgent = userAgent
                        break
                    }
                }
                val bytes = acceptedBytes ?: error(
                    "订阅服务仍返回“客户端已过时”，当前配置未被覆盖",
                )
                DiagnosticLog.record(
                    "PROFILE",
                    "download accepted; host=${URL(url).host}; userAgent=$acceptedUserAgent; bytes=${bytes.size}",
                )
                profileMutationBlockedMessage()?.let(::error)
                writeConfig(bytes, URL(url).host, url, acceptedUserAgent)
                mainHandler.post { callback(true, "订阅已通过兼容模式导入") }
            }.onFailure {
                DiagnosticLog.record("PROFILE/ERROR", it.stackTraceToString())
                mainHandler.post { callback(false, it.message ?: "下载失败") }
            }
        }
    }

    private fun downloadProfileBytes(url: String, userAgent: String): ByteArray {
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 20_000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", userAgent)
            connection.setRequestProperty(
                "Accept",
                "application/yaml, text/yaml, text/plain, */*",
            )
            val responseCode = connection.responseCode
            require(responseCode in 200..299) { "订阅服务器返回 HTTP $responseCode" }
            connection.inputStream.use { stream ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                    require(output.size() <= MAX_PROFILE_BYTES) { "订阅超过 16 MB" }
                }
                output.toByteArray()
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun writeConfig(
        bytes: ByteArray,
        label: String,
        sourceUrl: String,
        compatibleUserAgent: String = ProfileCompatibility.DEFAULT_SUBSCRIPTION_USER_AGENT,
    ) {
        check(profileMutationBlockedMessage() == null) {
            "请先停止代理，再导入或刷新订阅"
        }
        require(bytes.isNotEmpty()) { "配置内容为空" }
        require(bytes.size <= MAX_PROFILE_BYTES) { "配置文件超过 16 MB" }
        val normalized = ProfileCompatibility.applyMode(
            ProfileCompatibility.normalize(bytes, compatibleUserAgent),
            AppState.mode,
        )
        val text = normalized.toString(Charsets.UTF_8)
        require(text.contains(":")) { "这不像 Clash YAML 配置" }
        val isSameUrlProfile = sourceUrl.isNotBlank() && sourceUrl == AppState.profileUrl
        profileRevision += 1L
        atomicWrite(configFile(), normalized, "profile")
        AppState.profileName = label.ifBlank { "本地配置" }
        AppState.profileUrl = sourceUrl
        AppState.lastError = ""
        if (!isSameUrlProfile) resetProxySelection()
        proxyCatalogCacheFile().delete()
        lastProxyCatalogCacheTime = 0L
        DiagnosticLog.record(
            "PROFILE",
            "config stored; label=$label; source=${DiagnosticSanitizer.sourceHost(sourceUrl)}; bytes=${normalized.size}",
        )
    }

    private fun profileMutationBlockedMessage(): String? = when (AppState.runState) {
        RunState.RUNNING -> "请先停止代理，再导入或刷新订阅"
        RunState.STARTING -> "代理状态切换中，请稍后再导入订阅"
        RunState.STOPPED, RunState.ERROR -> null
    }

    private fun resetProxySelection() {
        AppState.selectedMap = emptyMap()
        AppState.selectedNode = ""
        AppState.widgetGroup = ""
        AppState.widgetNode = "等待节点信息"
        AppState.widgetNodeFlag = "🌐"
    }

    private fun discardInvalidSelections(catalog: ProxyCatalog) {
        val groups = catalog.groups.associateBy { it.name }
        val previous = AppState.selectedMap
        val valid = previous.filter { (groupName, nodeName) ->
            val group = groups[groupName] ?: return@filter false
            group.remoteProviderPending || nodeName in group.nodes
        }
        if (valid == previous) return
        AppState.selectedMap = valid
        val selected = valid[AppState.widgetGroup]
        if (selected == null) {
            AppState.widgetGroup = ""
            AppState.selectedNode = ""
            AppState.widgetNode = "等待节点信息"
            AppState.widgetNodeFlag = "🌐"
        } else {
            AppState.selectedNode = selected
        }
        ProxyWidgetProvider.updateAll(app, AppState.runState)
    }

    private fun invoke(method: String, data: Any?, callback: (JSONObject?) -> Unit) {
        withRemote { service ->
            worker.execute {
                runCatching {
                    val action = JSONObject()
                        .put("id", UUID.randomUUID().toString())
                        .put("method", method)
                        .put("data", data ?: JSONObject.NULL)
                    service.invokeAction(action.toString(), chunkCallback { raw ->
                        val result = runCatching { JSONObject(raw) }.getOrNull()
                        mainHandler.post { callback(result) }
                    })
                }.onFailure {
                    DiagnosticLog.record("CORE/ERROR", "invoke $method failed: ${it.stackTraceToString()}")
                    mainHandler.post { callback(null) }
                }
            }
        }
    }

    private fun actionError(result: JSONObject?, fallback: String): String {
        if (result == null) return fallback
        if (result.optInt("code", -1) != 0) {
            return result.optString("data").ifBlank { fallback }
        }
        return result.optString("data")
    }

    private fun applyCoreMode(mode: String, callback: (String) -> Unit) {
        val patch = JSONObject().put("mode", mode).toString()
        invoke("updateConfig", patch) { result ->
            callback(actionError(result, "切换代理模式失败"))
        }
    }

    private fun persistMode(mode: String, callback: (String) -> Unit) {
        worker.execute {
            val message = runCatching {
                val file = configFile()
                val updated = ProfileCompatibility.applyMode(file.readBytes(), mode)
                atomicWrite(file, updated, "mode")
                AppState.mode = mode
                DiagnosticLog.record("CONFIG", "proxy mode changed to $mode")
                ""
            }.getOrElse {
                DiagnosticLog.record("CONFIG/ERROR", it.stackTraceToString())
                it.message ?: "保存代理模式失败"
            }
            mainHandler.post { callback(message) }
        }
    }

    private fun atomicWrite(target: File, bytes: ByteArray, purpose: String) {
        val temp = File(target.parentFile, ".${target.name}.$purpose.tmp")
        runCatching {
            FileOutputStream(temp).use { output ->
                output.write(bytes)
                output.fd.sync()
            }
            Os.rename(temp.absolutePath, target.absolutePath)
        }.onFailure {
            temp.delete()
            throw it
        }
    }

    private fun cacheProxyCatalog(data: JSONObject, requestedRevision: Long) {
        if (AppState.runState != RunState.RUNNING || requestedRevision != profileRevision) return
        val now = System.currentTimeMillis()
        if (now - lastProxyCatalogCacheTime < WIDGET_NODE_REFRESH_MS) return
        val freshCatalog = ProfileNodeCatalog.fromRuntime(data, AppState.selectedMap)
        val preserveMoreComplete = now < proxyCatalogWarmupUntil
        worker.execute {
            runCatching {
                if (requestedRevision != profileRevision) return@runCatching
                val configBytes = configFile().readBytes()
                val catalog = if (preserveMoreComplete) {
                    ProfileNodeCatalog.preferMoreCompleteDuringWarmup(
                        fresh = freshCatalog,
                        cached = ProfileNodeCatalog.load(proxyCatalogCacheFile(), configBytes),
                    )
                } else {
                    freshCatalog
                }
                ProfileNodeCatalog.save(
                    cacheFile = proxyCatalogCacheFile(),
                    configBytes = configBytes,
                    catalog = catalog,
                )
                mainHandler.post {
                    if (requestedRevision == profileRevision) {
                        lastProxyCatalogCacheTime = System.currentTimeMillis()
                    }
                }
            }.onFailure {
                DiagnosticLog.record("PROXY/ERROR", "catalog cache failed: ${it.message}")
            }
        }
    }

    private fun beginProxyCatalogWarmup() {
        if (proxyCatalogWarmupUntil != 0L) return
        proxyCatalogWarmupUntil = System.currentTimeMillis() + PROXY_CATALOG_WARMUP_MS
        val revision = profileRevision
        for (delay in PROXY_CATALOG_WARMUP_DELAYS) {
            mainHandler.postDelayed({
                if (AppState.runState == RunState.RUNNING && revision == profileRevision) {
                    lastProxyCatalogCacheTime = 0L
                    getProxies {}
                }
            }, delay)
        }
    }

    private fun installEventListener(service: IRemoteInterface) {
        runCatching {
            val buffers = mutableMapOf<String, ByteArrayOutputStream>()
            service.setEventListener(object : IEventInterface.Stub() {
                override fun onEvent(
                    id: String,
                    data: ByteArray?,
                    isSuccess: Boolean,
                    ack: IAckInterface?,
                ) {
                    val output = synchronized(buffers) {
                        buffers.getOrPut(id) { ByteArrayOutputStream() }
                    }
                    data?.let { output.write(it) }
                    ack?.onAck()
                    if (isSuccess) {
                        synchronized(buffers) { buffers.remove(id) }
                        val event = runCatching {
                            JSONObject(output.toString(Charsets.UTF_8.name()))
                        }.getOrNull() ?: return
                        mainHandler.post {
                            DiagnosticLog.recordCoreEvent(event)
                            updateWidgetNodeFromEvent(event)
                            for (listener in listeners) listener.onCoreEvent(event)
                        }
                    }
                }
            })
        }
    }

    private fun chunkCallback(onComplete: (String) -> Unit): ICallbackInterface {
        val output = ByteArrayOutputStream()
        return object : ICallbackInterface.Stub() {
            override fun onResult(data: ByteArray?, isSuccess: Boolean, ack: IAckInterface?) {
                data?.let { output.write(it) }
                ack?.onAck()
                if (isSuccess) {
                    onComplete(output.toString(Charsets.UTF_8.name()))
                }
            }
        }
    }

    private fun withRemote(action: (IRemoteInterface) -> Unit) {
        val current = remote
        if (current != null) {
            action(current)
            return
        }
        pending += action
        bind()
    }

    private fun bind() {
        if (binding || remote != null || !::app.isInitialized) return
        binding = true
        runCatching {
            app.bindService(Intent(app, RemoteService::class.java), connection, Context.BIND_AUTO_CREATE)
        }.onFailure {
            binding = false
            updateState(RunState.ERROR, it.message ?: "无法绑定后台服务")
        }
    }

    private fun updateState(state: RunState, message: String) {
        DiagnosticLog.record("STATE", "$state${if (message.isBlank()) "" else ": $message"}")
        AppState.runState = state
        AppState.lastError = if (state == RunState.ERROR) message else ""
        mainHandler.post {
            mainHandler.removeCallbacks(widgetTicker)
            if (state == RunState.RUNNING) {
                beginProxyCatalogWarmup()
                mainHandler.post(widgetTicker)
            } else if (state == RunState.STOPPED) {
                proxyCatalogWarmupUntil = 0L
                AppState.runTime = 0L
                AppState.widgetUp = 0L
                AppState.widgetDown = 0L
            } else if (state == RunState.ERROR) {
                proxyCatalogWarmupUntil = 0L
            }
            for (listener in listeners) listener.onStateChanged(state, message)
            ProxyWidgetProvider.updateAll(app, state)
            TileService.requestRefresh(app)
        }
    }

    private fun notifyTraffic(up: Long, down: Long) {
        mainHandler.post {
            AppState.widgetUp = up
            AppState.widgetDown = down
            for (listener in listeners) listener.onTraffic(up, down)
            val now = System.currentTimeMillis()
            if (now - lastWidgetRenderTime >= WIDGET_RENDER_THROTTLE_MS) {
                lastWidgetRenderTime = now
                ProxyWidgetProvider.updateAll(app, AppState.runState)
            }
        }
    }

    private fun refreshExitIp(attemptTime: Long) {
        if (exitIpRefreshRunning) return
        exitIpRefreshRunning = true
        lastExitIpAttemptTime = attemptTime
        widgetWorker.execute {
            val identity = ExitIpResolver.resolve()
            mainHandler.post {
                exitIpRefreshRunning = false
                if (identity != null) {
                    AppState.widgetExitIp = identity.ip
                    AppState.widgetExitCountry = identity.countryCode
                    AppState.widgetExitRefreshTime = System.currentTimeMillis()
                    ProxyWidgetProvider.updateAll(app, AppState.runState)
                }
            }
        }
    }

    private fun updateWidgetNodeFromProxies(data: JSONObject) {
        val proxies = data.optJSONObject("proxies") ?: return
        val all = data.optJSONArray("all") ?: JSONArray()
        val selected = AppState.selectedMap.toMutableMap()
        for (index in 0 until all.length()) {
            val group = all.optString(index)
            val now = proxies.optJSONObject(group)?.optString("now").orEmpty()
            if (group.isNotBlank() && now.isNotBlank()) selected[group] = now
        }
        if (selected != AppState.selectedMap) AppState.selectedMap = selected
        selected[AppState.widgetGroup]?.takeIf { it.isNotBlank() }?.let {
            if (it != AppState.selectedNode) AppState.selectedNode = it
        }

        val candidates = linkedSetOf<String>()
        if (AppState.mode == "global" && proxies.has("GLOBAL")) {
            candidates += "GLOBAL"
        }
        AppState.widgetGroup.takeIf(proxies::has)?.let(candidates::add)
        for (index in 0 until all.length()) {
            val name = all.optString(index)
            val proxy = proxies.optJSONObject(name) ?: continue
            if (name != "GLOBAL" && proxy.optString("now").isNotBlank()) {
                candidates += name
            }
        }
        if (candidates.isEmpty() && proxies.has("GLOBAL")) candidates += "GLOBAL"
        val node = candidates.asSequence()
            .map { resolveProxyLeaf(it, proxies) }
            .firstOrNull { it.isNotBlank() }
            ?: return
        if (node != AppState.widgetNode) {
            AppState.widgetNode = node
            AppState.widgetNodeFlag = WidgetInfo.flagFromNodeName(node)
            ProxyWidgetProvider.updateAll(app, AppState.runState)
        }
    }

    private fun resolveProxyLeaf(start: String, proxies: JSONObject): String {
        var current = start
        val visited = mutableSetOf<String>()
        repeat(16) {
            if (!visited.add(current)) return current
            val proxy = proxies.optJSONObject(current) ?: return current
            val next = proxy.optString("now")
            if (next.isBlank() || next == current) return current
            current = next
        }
        return current
    }

    private fun updateWidgetNodeFromEvent(event: JSONObject) {
        if (event.optString("type") != "request") return
        val chains = event.optJSONObject("data")?.optJSONArray("chains") ?: return
        val node = chains.optString(0).trim()
        if (node.isBlank() || node in setOf("DIRECT", "REJECT") || node == AppState.widgetNode) return
        AppState.widgetNode = node
        AppState.widgetNodeFlag = WidgetInfo.flagFromNodeName(node)
        ProxyWidgetProvider.updateAll(app, AppState.runState)
    }

    private fun ensureDefaultConfig() {
        val target = configFile()
        if (target.exists()) return
        target.writeText(
            """
            mixed-port: 7890
            mode: rule
            log-level: info
            ipv6: false
            proxies: []
            proxy-groups: []
            rules:
              - MATCH,DIRECT
            """.trimIndent() + "\n",
        )
    }

    private fun prepareRuntimeConfig() {
        val file = configFile()
        if (!file.isFile) return
        val current = file.readBytes()
        val prepared = ProfileCompatibility.applyMode(
            ProfileCompatibility.enableDiagnosticLogging(
                ProfileCompatibility.disableGeoAutoUpdate(current),
            ),
            AppState.mode,
        )
        if (!current.contentEquals(prepared)) file.writeBytes(prepared)
    }
}
