package com.follow.clash

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.provider.MediaStore
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.integration.android.IntentIntegrator
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

class MainActivity : Activity(), ServiceController.Listener {
    private companion object {
        const val REQUEST_VPN = 4101
        const val REQUEST_CONFIG_FILE = 4102
        const val REQUEST_EXPORT_CONFIG = 4103
        const val REQUEST_GALLERY_QR = 4104
        const val REQUEST_EXPORT_DIAGNOSTIC = 4106
        val PROXY_MODES = listOf(
            Triple("rule", "规则", "按订阅规则自动分流"),
            Triple("global", "全局", "所有流量使用所选节点"),
            Triple("direct", "直连", "所有流量不经过代理"),
        )
    }

    private val nightMode: Boolean
        get() = resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    private val compactLayout: Boolean
        get() = resources.configuration.screenWidthDp < 360
    private val colors by lazy { AppColors.forNight(nightMode) }
    private val COLOR_PRIMARY get() = colors.primary
    private val COLOR_BACKGROUND get() = colors.background
    private val COLOR_TEXT get() = colors.text
    private val COLOR_MUTED get() = colors.muted

    private lateinit var content: FrameLayout
    private var statusText: TextView? = null
    private var profileText: TextView? = null
    private var uploadText: TextView? = null
    private var downloadText: TextView? = null
    private var durationText: TextView? = null
    private var heroHintText: TextView? = null
    private var toggleButton: Button? = null
    private lateinit var nav: LinearLayout
    private lateinit var toolbarStatus: TextView
    private lateinit var toolbarSubtitle: TextView
    private var selectedNodeText: TextView? = null
    private var catalogFallbackNode = ""
    private var modeStatusText: TextView? = null
    private val modeButtons = linkedMapOf<String, Button>()
    private val handler = Handler(Looper.getMainLooper())
    private val recentRequests = ArrayDeque<JSONObject>()
    private var page = 0
    private var proxyPageRunState: RunState? = null
    private var connectionPageRunState: RunState? = null
    private var proxyRequestGeneration = 0L
    private var proxyQuery = ""
    private var proxyGroupFilter = ""
    private val proxyDelays = mutableMapOf<Pair<String, String>, Int>()
    private var modeSwitchPending = false
    private var visibleScroll: ScrollView? = null
    private val pageScrollPositions = IntArray(4)

    private val trafficTicker = object : Runnable {
        override fun run() {
            ServiceController.updateTraffic()
            updateConnectionTime()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ServiceController.init(application)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        window.setSoftInputMode(
            android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN,
        )
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = !nightMode
            isAppearanceLightNavigationBars = !nightMode
        }
        proxyQuery = savedInstanceState?.getString("proxy_query").orEmpty()
        proxyGroupFilter = savedInstanceState?.getString("proxy_group").orEmpty()
        val root = buildRoot()
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val safeArea = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or
                    WindowInsetsCompat.Type.displayCutout(),
            )
            view.setPadding(
                dp(16) + safeArea.left,
                dp(12) + safeArea.top,
                dp(16) + safeArea.right,
                dp(8) + safeArea.bottom,
            )
            insets
        }
        ViewCompat.requestApplyInsets(root)
        showPage((savedInstanceState?.getInt("page", 0) ?: 0).coerceIn(0, 3))
        handleIncomingIntent(intent)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 4105)
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        ServiceController.addListener(this)
        ServiceController.syncState()
        handler.post(trafficTicker)
    }

    override fun onStop() {
        handler.removeCallbacks(trafficTicker)
        ServiceController.removeListener(this)
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("page", page)
        outState.putString("proxy_query", proxyQuery)
        outState.putString("proxy_group", proxyGroupFilter)
        super.onSaveInstanceState(outState)
    }

    override fun onStateChanged(state: RunState, message: String) {
        profileText?.text = AppState.profileName
        val status = when (state) {
            RunState.STOPPED -> "未连接"
            RunState.STARTING -> "切换中"
            RunState.RUNNING -> "已连接"
            RunState.ERROR -> "连接异常"
        }
        if (::toolbarStatus.isInitialized) {
            toolbarStatus.text = "●  $status"
            toolbarStatus.setTextColor(when (state) {
                RunState.RUNNING -> colors.success
                RunState.ERROR -> colors.error
                else -> COLOR_MUTED
            })
        }
        statusText?.text = when (state) {
            RunState.RUNNING -> "连接已开启"
            RunState.STARTING -> if (message == "正在停止") "正在断开连接" else "正在建立连接"
            RunState.ERROR -> "连接需要检查"
            RunState.STOPPED -> "随时，轻松连接"
        }
        heroHintText?.text = when (state) {
            RunState.RUNNING -> "代理正在运行，流量按当前模式转发"
            RunState.STARTING -> "请稍候，连接状态即将更新"
            RunState.ERROR -> message.ifBlank { "可在设置中查看和导出诊断日志" }
            RunState.STOPPED -> "选择喜欢的节点，然后开启连接"
        }
        toggleButton?.text = when (state) {
            RunState.RUNNING -> "断开连接"
            RunState.STARTING -> "处理中…"
            RunState.STOPPED, RunState.ERROR -> "开启连接"
        }
        toggleButton?.isEnabled = state != RunState.STARTING
        toggleButton?.alpha = if (state == RunState.STARTING) 0.65f else 1f
        updateConnectionTime()
        updateSelectedNodeText()
        updateModeControls()
        if (page == 1 && proxyPageRunState != state) {
            handler.post {
                if (page == 1 && proxyPageRunState != state) showPage(1)
            }
        }
        if (page == 2 && connectionPageRunState != state) {
            handler.post {
                if (page == 2 && connectionPageRunState != state) showPage(2)
            }
        }
        if (message.isNotBlank() && message !in setOf("正在启动", "正在停止")) {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onTraffic(up: Long, down: Long) {
        uploadText?.text = "${formatBytes(up)}/s"
        downloadText?.text = "${formatBytes(down)}/s"
        updateSelectedNodeText()
    }

    private fun updateConnectionTime() {
        val elapsed = if (AppState.runState == RunState.RUNNING && AppState.runTime > 0L) {
            (System.currentTimeMillis() - AppState.runTime).coerceAtLeast(0L) / 1000L
        } else 0L
        durationText?.text = String.format(
            Locale.ROOT,
            "%02d:%02d:%02d",
            elapsed / 3600L,
            (elapsed / 60L) % 60L,
            elapsed % 60L,
        )
    }

    override fun onCoreEvent(event: JSONObject) {
        if (event.optString("type") == "request") {
            event.optJSONObject("data")?.let {
                recentRequests.addFirst(it)
                while (recentRequests.size > 100) recentRequests.removeLast()
            }
            updateSelectedNodeText()
        }
    }

    private fun buildRoot(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(COLOR_BACKGROUND)
            setPadding(dp(16), dp(12), dp(16), dp(8))
        }
        root.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), dp(4), dp(2), dp(12))
            addView(iconTile(UiIcon.SHIELD, 38), square(38))
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(10), 0, 0, 0)
                addView(label("Clash Next", if (compactLayout) 16f else 19f, bold = true).apply {
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                })
                toolbarSubtitle = label("你的轻量网络助手", 11f, COLOR_MUTED)
                if (compactLayout) toolbarSubtitle.visibility = View.GONE
                addView(toolbarSubtitle)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            toolbarStatus = label("●  未连接", 11f, COLOR_MUTED).apply {
                background = rounded(colors.surface, 20f)
                setPadding(dp(10), dp(7), dp(10), dp(7))
            }
            addView(toolbarStatus)
        }, matchHeight(if (compactLayout) 56 else 66))
        content = FrameLayout(this)
        root.addView(content, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            0,
            1f,
        ))
        nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = outlined(colors.surface, 24f, colors.outline)
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        val destinations = listOf(
            "概览" to UiIcon.HOME,
            "节点" to UiIcon.NODES,
            "连接" to UiIcon.CONNECTIONS,
            "设置" to UiIcon.SETTINGS,
        )
        destinations.forEachIndexed { index, (title, icon) ->
            nav.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                minimumHeight = dp(60)
                background = ripple(colors.surface, 20f)
                addView(iconView(icon, COLOR_MUTED).apply {
                    setPadding(dp(15), dp(5), dp(15), dp(5))
                }, LinearLayout.LayoutParams(dp(54), dp(32)))
                addView(label(title, 11f, COLOR_MUTED).apply {
                    gravity = Gravity.CENTER
                    setPadding(0, dp(2), 0, 0)
                })
                contentDescription = title
                isFocusable = true
                setOnClickListener { if (page != index) showPage(index) }
            }, LinearLayout.LayoutParams(0, dp(62), 1f))
        }
        root.addView(nav, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(72),
        ).apply { topMargin = dp(8) })
        return root
    }

    private fun showPage(index: Int) {
        visibleScroll?.let { pageScrollPositions[page] = it.scrollY }
        page = index
        proxyRequestGeneration += 1L
        statusText = null
        profileText = null
        uploadText = null
        downloadText = null
        durationText = null
        heroHintText = null
        toggleButton = null
        selectedNodeText = null
        modeStatusText = null
        modeButtons.clear()
        content.removeAllViews()
        content.addView(
            when (index) {
                0 -> buildHome()
                1 -> buildProxies()
                2 -> buildConnections()
                else -> buildSettings()
            },
        )
        toolbarSubtitle.text = listOf("你的轻量网络助手", "找到适合你的连接", "实时连接与请求", "按你的习惯使用")[index]
        for (i in 0 until nav.childCount) {
            val item = nav.getChildAt(i) as LinearLayout
            val selected = i == index
            val image = item.getChildAt(0) as ImageView
            image.setColorFilter(if (selected) COLOR_PRIMARY else COLOR_MUTED)
            image.background = rounded(
                if (selected) colors.primarySoft else Color.TRANSPARENT,
                18f,
            )
            val text = item.getChildAt(1) as TextView
            text.setTextColor(if (selected) COLOR_PRIMARY else COLOR_MUTED)
            text.typeface = if (selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            item.isSelected = selected
            item.contentDescription = "${text.text}${if (selected) "，当前页面" else ""}"
        }
        val scroll = visibleScroll
        scroll?.post {
            if (page == index && visibleScroll === scroll) {
                scroll.scrollTo(0, pageScrollPositions[index])
            }
        }
        onStateChanged(AppState.runState, "")
    }

    private fun buildHome(): View {
        catalogFallbackNode = ""
        val requestGeneration = proxyRequestGeneration
        val list = verticalScroll()
        list.addView(pageHeading("网络概览", "连接状态，一眼就知道"))
        list.addView(card().apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xFF17253F.toInt(), 0xFF2B4676.toInt()),
            ).apply { cornerRadius = dp(28).toFloat() }
            setPadding(dp(22), dp(22), dp(22), dp(22))
            addView(label("连接状态", 11f, 0xFFAFC4EA.toInt(), true))
            val heading = label("随时，轻松连接", if (compactLayout) 23f else 27f, Color.WHITE, true).apply {
                setPadding(0, dp(9), 0, 0)
            }
            statusText = heading
            addView(heading)
            val hint = label("选择喜欢的节点，然后开启连接", 12f, 0xFFBBCBE4.toInt()).apply {
                setPadding(0, dp(7), 0, dp(20))
                maxLines = 2
            }
            heroHintText = hint
            addView(hint)
            addView(LinearLayout(this@MainActivity).apply {
                gravity = Gravity.CENTER_VERTICAL
                background = ripple(0x19FFFFFF, 17f)
                setPadding(dp(12), dp(12), dp(10), dp(12))
                addView(iconView(UiIcon.NODES, 0xFFD4E2FC.toInt()), square(23))
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(10), 0, dp(6), 0)
                    addView(label("所选节点 · 点击切换", 10f, 0xFFB8CAE8.toInt()))
                    val node = label("选择一个节点", if (compactLayout) 14f else 16f, Color.WHITE, true).apply {
                        maxLines = 2
                        ellipsize = TextUtils.TruncateAt.END
                        setPadding(0, dp(4), 0, 0)
                    }
                    selectedNodeText = node
                    addView(node)
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(iconView(UiIcon.CHEVRON, 0xFFB8CAE8.toInt()), square(18))
                minimumHeight = dp(72)
                setOnClickListener { showPage(1) }
            })
            addView(LinearLayout(this@MainActivity).apply {
                setPadding(0, dp(20), 0, dp(20))
                val duration = heroMetric("连接时长", "00:00:00")
                val upload = heroMetric("↑ 上传", "0 B/s")
                val download = heroMetric("↓ 下载", "0 B/s")
                durationText = duration.getChildAt(1) as TextView
                uploadText = upload.getChildAt(1) as TextView
                downloadText = download.getChildAt(1) as TextView
                listOf(duration, upload, download).forEach {
                    addView(it, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                }
            })
            val toggle = primaryButton("开启连接").apply {
                setTextColor(0xFF243B65.toInt())
                background = ripple(Color.WHITE, 18f)
                setButtonIcon(this, UiIcon.POWER, 0xFF243B65.toInt())
                setOnClickListener { ServiceController.requestToggle() }
            }
            toggleButton = toggle
            addView(toggle, matchHeight(54))
        })
        if (ServiceController.hasUnsupportedClientProfile()) {
            list.addView(sectionTitle("订阅需要重新获取"))
            list.addView(card().apply {
                addView(infoCard(
                    "检测到当前配置是订阅服务返回的“客户端已过时”提示配置。" +
                        "新版会自动尝试多种兼容方式，并且不会再保存这种提示配置。",
                ))
                if (AppState.profileUrl.isNotBlank()) {
                    addView(rowButton("重新获取兼容订阅") { refreshProfile() })
                }
            })
        }
        list.addView(sectionTitle("代理模式"))
        list.addView(card().apply {
            setPadding(dp(10), dp(10), dp(10), dp(14))
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                background = rounded(colors.surfaceSoft, 17f)
                setPadding(dp(4), dp(4), dp(4), dp(4))
                PROXY_MODES.forEachIndexed { index, (mode, label, _) ->
                    val button = secondaryButton(label).apply {
                        setOnClickListener { selectMode(mode, label) }
                    }
                    modeButtons[mode] = button
                    addView(button, LinearLayout.LayoutParams(0, dp(48), 1f).apply {
                        if (index != PROXY_MODES.lastIndex) marginEnd = dp(4)
                    })
                }
            })
            val summary = label("", 12f, COLOR_MUTED).apply {
                setPadding(dp(6), dp(12), dp(6), 0)
            }
            modeStatusText = summary
            addView(summary)
        })
        updateModeControls()
        list.addView(sectionTitle("当前订阅"))
        list.addView(card().apply {
            setPadding(dp(16), dp(15), dp(10), dp(15))
            addView(LinearLayout(this@MainActivity).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(iconTile(UiIcon.FOLDER, 42), square(42))
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(12), 0, 0, 0)
                    val name = label(AppState.profileName, 16f, bold = true).apply {
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.END
                    }
                    profileText = name
                    addView(name)
                    addView(label(
                        if (AppState.profileUrl.isBlank()) "本地配置 · 随时可替换" else "URL 订阅 · 支持手动更新",
                        11f,
                        COLOR_MUTED,
                    ).apply { setPadding(0, dp(4), 0, 0) })
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(iconAction(UiIcon.REFRESH, "更新当前订阅") { refreshProfile() }, square(48))
            })
        })
        list.addView(LinearLayout(this).apply {
            setPadding(0, dp(10), 0, 0)
            addView(quickTile(UiIcon.PLUS, "导入订阅", "链接或配置文件") { showImportOptions() },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = dp(10)
                })
            addView(quickTile(UiIcon.QR, "扫描二维码", "相机或相册识别") { showQrOptions() },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        })
        list.addView(footer())
        updateSelectedNodeText()
        ServiceController.getProxyCatalog { catalog ->
            if (page == 0 && requestGeneration == proxyRequestGeneration && catalog != null) {
                updateCatalogFallback(catalog)
            }
        }
        return list.parent as View
    }

    private fun buildProxies(): View {
        proxyPageRunState = AppState.runState
        val requestGeneration = proxyRequestGeneration
        val list = verticalScroll()
        list.addView(pageHeading("代理节点", "按地区搜索，轻触节点即可选择"))
        list.addView(card().apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val inset = if (compactLayout) 12 else 16
            val iconSize = if (compactLayout) 36 else 44
            setPadding(dp(inset), dp(inset), dp(inset), dp(inset))
            addView(iconTile(UiIcon.NODES, iconSize), square(iconSize))
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), 0, 0, 0)
                addView(label(
                    if (AppState.runState == RunState.RUNNING) "正在使用" else "当前选择 · 连接时生效",
                    if (compactLayout) 10f else 11f,
                    COLOR_MUTED,
                ))
                val nodeView = label("", if (compactLayout) 14f else 16f, bold = true).apply {
                    maxLines = if (compactLayout) 3 else 2
                    ellipsize = TextUtils.TruncateAt.END
                    setPadding(0, dp(5), 0, 0)
                }
                selectedNodeText = nodeView
                addView(nodeView)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        })
        updateSelectedNodeText()
        val summary = label("正在读取订阅节点…", 12f, COLOR_MUTED).apply {
            setPadding(dp(3), dp(14), 0, dp(10))
        }
        list.addView(summary)
        val search = EditText(this).apply {
            hint = "搜索节点名称或地区"
            textSize = 14f
            setTextColor(COLOR_TEXT)
            setHintTextColor(COLOR_MUTED)
            background = null
            setSingleLine(true)
            setPadding(dp(10), 0, 0, 0)
            setText(proxyQuery)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
        }
        list.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = outlined(colors.surface, 18f, colors.outline)
            setPadding(dp(14), 0, dp(4), 0)
            addView(iconView(UiIcon.SEARCH, COLOR_MUTED), square(20))
            addView(search, LinearLayout.LayoutParams(0, dp(52), 1f))
            addView(iconAction(UiIcon.PLUS, "清空搜索", rotated = true) {
                search.setText("")
            }, square(48))
        })
        val chips = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, dp(6))
        }
        list.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(chips)
        })
        val results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        list.addView(results)
        val loading = ProgressBar(this)
        results.addView(loading, matchHeight(64))
        var loadedCatalog: ProxyCatalog? = null
        fun renderResults() {
            loadedCatalog?.let {
                results.removeAllViews()
                renderProxyGroups(results, it)
            }
        }
        fun renderChips(catalog: ProxyCatalog) {
            chips.removeAllViews()
            val groups = listOf("__all__" to "全部分组") + catalog.groups
                .filter { it.nodes.isNotEmpty() }
                .map { it.name to if (it.name == "GLOBAL") "全局" else it.name }
            for ((name, title) in groups) {
                val selected = name == proxyGroupFilter
                chips.addView(secondaryButton(title).apply {
                    textSize = 12f
                    setPadding(dp(15), 0, dp(15), 0)
                    setTextColor(if (selected) colors.onPrimary else COLOR_MUTED)
                    background = ripple(
                        if (selected) COLOR_PRIMARY else colors.surface,
                        18f,
                    )
                    isSelected = selected
                    setOnClickListener {
                        proxyGroupFilter = name
                        renderChips(catalog)
                        renderResults()
                    }
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)).apply {
                    marginEnd = dp(7)
                })
            }
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(text: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(text: CharSequence?, start: Int, before: Int, count: Int) {
                proxyQuery = text?.toString().orEmpty()
                renderResults()
            }
            override fun afterTextChanged(text: Editable?) = Unit
        })
        ServiceController.getProxyCatalog { catalog ->
            if (page != 1 || requestGeneration != proxyRequestGeneration) {
                return@getProxyCatalog
            }
            results.removeAllViews()
            if (catalog == null) {
                summary.text = "暂时无法读取节点"
                results.addView(emptyState(
                    UiIcon.NODES, "节点没有载入", "检查订阅配置后，再重新打开这个页面。",
                ))
                return@getProxyCatalog
            }
            updateCatalogFallback(catalog)
            if (catalog.groups.isEmpty()) {
                results.addView(emptyState(
                    UiIcon.NODES, "还没有可用节点", "先导入订阅，即可在这里查看和选择节点。",
                ))
                return@getProxyCatalog
            }
            loadedCatalog = catalog
            if (proxyGroupFilter != "__all__" && catalog.groups.none { it.name == proxyGroupFilter }) {
                proxyGroupFilter = when {
                    AppState.mode == "global" -> "GLOBAL"
                    catalog.groups.any { it.name == AppState.widgetGroup } -> AppState.widgetGroup
                    else -> catalog.groups.firstOrNull { it.name != "GLOBAL" }?.name
                        ?: catalog.groups.first().name
                }
            }
            summary.text = if (compactLayout) {
                "${catalog.nodeCount} 节点 · ${catalog.groupCount} 分组 · " +
                    if (catalog.isLive) "实时" else "离线可选"
            } else {
                "${catalog.nodeCount} 个节点 · ${catalog.groupCount} 个分组 · " +
                    if (catalog.isLive) "实时状态" else "离线可选，连接后可测速"
            }
            renderChips(catalog)
            if (catalog.pendingProviderGroups.isNotEmpty()) {
                list.addView(infoCard("部分远程节点首次连接后载入，之后可离线查看。"))
            }
            renderResults()
        }
        return list.parent as View
    }

    private fun updateSelectedNodeText() {
        val node = AppState.displayNode().trim().takeUnless {
            it.isBlank() || it == "等待节点信息"
        } ?: catalogFallbackNode
        val flag = WidgetInfo.flagFromNodeName(node)
        selectedNodeText?.text = when {
            node.isBlank() || node == "等待节点信息" -> "尚未选择节点"
            else -> "$flag  ${node.removePrefix(flag).trim()}"
        }
    }

    private fun updateCatalogFallback(catalog: ProxyCatalog) {
        val groups = catalog.groups.associateBy { it.name }
        val initial = when {
            AppState.mode == "global" -> groups["GLOBAL"]
            groups.containsKey(AppState.widgetGroup) -> groups[AppState.widgetGroup]
            else -> catalog.groups.firstOrNull { it.name != "GLOBAL" } ?: catalog.groups.firstOrNull()
        }
        var current = initial?.selected.orEmpty()
        val visited = mutableSetOf<String>()
        for (depth in 0 until 16) {
            if (!visited.add(current)) break
            current = groups[current]?.selected ?: break
        }
        catalogFallbackNode = current
        updateSelectedNodeText()
    }

    private fun renderProxyGroups(list: LinearLayout, catalog: ProxyCatalog) {
        var visibleCount = 0
        for (group in catalog.groups) {
            if (proxyGroupFilter != "__all__" && group.name != proxyGroupFilter) continue
            val nodes = group.nodes.filter { it.contains(proxyQuery.trim(), ignoreCase = true) }
            if (nodes.isEmpty()) continue
            visibleCount += nodes.size
            list.addView(sectionTitle(
                "${if (group.name == "GLOBAL") "全局节点" else group.name}  ·  ${nodes.size}",
            ))
            val groupCard = card().apply { setPadding(dp(6), dp(6), dp(6), dp(6)) }
            for ((index, nodeName) in nodes.withIndex()) {
                val isSelected = nodeName == group.selected
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    minimumHeight = dp(78)
                    setPadding(dp(10), dp(10), dp(8), dp(10))
                    background = ripple(
                        if (isSelected) colors.primarySoft else colors.surface,
                        17f,
                    )
                }
                val flag = WidgetInfo.flagFromNodeName(nodeName)
                row.addView(label(flag, 23f).apply {
                    gravity = Gravity.CENTER
                    background = rounded(colors.surfaceSoft, 13f)
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }, square(40))
                row.addView(LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(11), 0, dp(5), 0)
                    addView(label(
                        nodeName.removePrefix(flag).trim(),
                        14f,
                        if (isSelected) COLOR_PRIMARY else COLOR_TEXT,
                        bold = isSelected,
                    ).apply {
                        maxLines = 2
                        ellipsize = TextUtils.TruncateAt.END
                    })
                    addView(label(when {
                        isSelected -> "已选择"
                        !group.selectable -> "仅供查看"
                        AppState.runState == RunState.RUNNING -> "轻触切换"
                        else -> "轻触预选 · 连接时生效"
                    }, 10f, COLOR_MUTED).apply { setPadding(0, dp(5), 0, 0) })
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                val delay = proxyDelays[group.name to nodeName]
                row.addView(secondaryButton(when {
                    delay == null -> if (AppState.runState == RunState.RUNNING) "测速" else "—"
                    delay > 0 -> "${delay}ms"
                    else -> "超时"
                }).apply {
                    textSize = 11f
                    setPadding(dp(2), 0, dp(2), 0)
                    background = ripple(Color.TRANSPARENT, 13f)
                    isEnabled = AppState.runState == RunState.RUNNING
                    alpha = if (isEnabled) 1f else 0.45f
                    contentDescription = "测量 $nodeName 的延迟"
                    setOnClickListener {
                        isEnabled = false
                        text = "…"
                        ServiceController.testDelay(nodeName, group.testUrl.ifBlank { null }) { result ->
                            proxyDelays[group.name to nodeName] = result
                            text = if (result > 0) "${result}ms" else "超时"
                            setTextColor(if (result in 1..250) colors.success else COLOR_MUTED)
                            isEnabled = AppState.runState == RunState.RUNNING
                        }
                    }
                }, LinearLayout.LayoutParams(dp(62), dp(48)))
                row.addView(FrameLayout(this).apply {
                    if (isSelected) {
                        background = rounded(COLOR_PRIMARY, 12f)
                        addView(iconView(UiIcon.CHECK, colors.onPrimary), FrameLayout.LayoutParams(
                            dp(14), dp(14), Gravity.CENTER,
                        ))
                    } else {
                        background = outlined(Color.TRANSPARENT, 12f, colors.outline)
                    }
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }, square(23))
                if (group.selectable && !isSelected && AppState.runState != RunState.STARTING) {
                    row.isFocusable = true
                    row.contentDescription = "$nodeName，${if (catalog.isLive) "切换到此节点" else "预选此节点"}"
                    row.setOnClickListener {
                        row.isEnabled = false
                        row.alpha = 0.6f
                        ServiceController.selectProxy(group.name, nodeName) { message ->
                            toast(
                                if (message.isBlank()) {
                                    if (AppState.runState == RunState.RUNNING) {
                                        "已选择 $nodeName"
                                    } else {
                                        "已预选 $nodeName，启动时生效"
                                    }
                                } else {
                                    message
                                },
                            )
                            if (message.isBlank() && page == 1) showPage(1)
                            else {
                                row.isEnabled = true
                                row.alpha = 1f
                            }
                        }
                    }
                }
                groupCard.addView(row)
                if (index != nodes.lastIndex) groupCard.addView(View(this), matchHeight(3))
            }
            list.addView(groupCard)
        }
        if (visibleCount == 0) list.addView(emptyState(
            UiIcon.SEARCH, "没有匹配的节点", "试试其他地区、关键词，或者切换一个分组。",
        ))
    }

    private fun selectMode(mode: String, label: String) {
        if (modeSwitchPending) return
        if (mode == AppState.mode) {
            toast("当前已是${label}模式")
            return
        }
        modeSwitchPending = true
        modeStatusText?.text = "正在切换到${label}模式…"
        modeButtons.values.forEach { it.isEnabled = false }
        ServiceController.updateMode(mode) { message ->
            modeSwitchPending = false
            updateModeControls()
            toast(if (message.isBlank()) {
                if (AppState.runState == RunState.RUNNING) "已切换到${label}模式"
                else "已保存${label}模式，连接时生效"
            } else message)
        }
    }

    private fun updateModeControls() {
        val current = AppState.mode
        val currentOption = PROXY_MODES.firstOrNull { it.first == current }
            ?: PROXY_MODES.first()
        if (!modeSwitchPending) {
            modeStatusText?.text = "当前${currentOption.second}模式 · ${currentOption.third}"
        }
        for ((mode, button) in modeButtons) {
            val option = PROXY_MODES.first { it.first == mode }
            val selected = mode == currentOption.first
            button.text = if (selected) "✓ ${option.second}" else option.second
            button.setTextColor(if (selected) colors.onPrimary else COLOR_MUTED)
            button.typeface = if (selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            button.background = ripple(
                if (selected) COLOR_PRIMARY else Color.TRANSPARENT,
                14f,
            )
            button.isSelected = selected
            button.contentDescription = if (selected) {
                "${option.second}模式，当前已选择"
            } else {
                "切换到${option.second}模式"
            }
            button.isEnabled = !modeSwitchPending && AppState.runState != RunState.STARTING
            button.alpha = if (button.isEnabled) 1f else 0.55f
        }
    }

    private fun buildConnections(): View {
        connectionPageRunState = AppState.runState
        val list = verticalScroll()
        val refresh = iconAction(UiIcon.REFRESH, "刷新活动连接") {}
        list.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(pageHeading("网络连接", "了解请求正在经过哪里"),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(refresh, square(48))
        })
        val summary = label("实时活动连接", 12f, COLOR_MUTED).apply {
            setPadding(dp(3), 0, 0, dp(12))
        }
        list.addView(summary)
        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        list.addView(container)
        fun load() {
            container.removeAllViews()
            if (AppState.runState != RunState.RUNNING) {
                summary.text = "当前未连接"
                container.addView(emptyState(
                    UiIcon.CONNECTIONS,
                    "连接记录将在这里出现",
                    "开启代理后，可以查看正在访问的目标和使用的节点。",
                ))
                container.addView(secondaryButton("前往概览").apply {
                    setOnClickListener { showPage(0) }
                }, matchHeight(50))
                return
            }
            refresh.isEnabled = false
            container.addView(ProgressBar(this), matchHeight(56))
            ServiceController.getConnections { connections ->
                if (page != 2) return@getConnections
                refresh.isEnabled = true
                container.removeAllViews()
                summary.text = "${connections.length()} 个活动连接 · 点击查看详情"
                if (connections.length() == 0) {
                    container.addView(emptyState(
                        UiIcon.CONNECTIONS, "暂时没有活动连接", "开始浏览或使用其他应用后，再刷新看看。",
                    ))
                }
                for (i in 0 until connections.length()) {
                    val item = connections.optJSONObject(i) ?: continue
                    val metadata = item.optJSONObject("metadata") ?: JSONObject()
                    val title = metadata.optString("host").ifBlank {
                        metadata.optString("destinationIP", "未知目标")
                    }
                    val detail = listOf(
                        metadata.optString("network").uppercase(Locale.ROOT),
                        item.optJSONArray("chains")?.optString(0).orEmpty(),
                    ).filter { it.isNotBlank() }.joinToString(" · ")
                    container.addView(card().apply {
                        addView(settingsRow(UiIcon.CONNECTIONS, title, detail) {
                            showJsonDetail("连接详情", item)
                        })
                    }, LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply { bottomMargin = dp(8) })
                }
            }
        }
        refresh.setOnClickListener { load() }
        load()
        list.addView(sectionTitle("最近请求"))
        if (recentRequests.isEmpty()) {
            list.addView(infoCard("新的请求会自动记录在这里，最多保留最近 100 条。"))
        } else {
            val requestsCard = card()
            recentRequests.take(30).forEach { request ->
                val metadata = request.optJSONObject("metadata")
                val title = metadata?.optString("host").orEmpty().ifBlank {
                    metadata?.optString("destinationIP").orEmpty().ifBlank { "请求详情" }
                }
                requestsCard.addView(settingsRow(
                    UiIcon.CONNECTIONS,
                    title,
                    request.optJSONArray("chains")?.optString(0).orEmpty(),
                ) {
                    showJsonDetail("请求详情", request)
                })
            }
            list.addView(requestsCard)
        }
        return list.parent as View
    }

    private fun buildSettings(): View {
        val list = verticalScroll()
        list.addView(pageHeading("设置", "把连接，调成你习惯的样子"))
        list.addView(sectionTitle("订阅与导入"))
        list.addView(card().apply {
            addView(settingsRow(UiIcon.PLUS, "导入订阅链接", "粘贴 HTTPS 订阅地址") { showUrlDialog() })
            addView(divider())
            addView(settingsRow(UiIcon.FOLDER, "导入本地配置", "从手机中选择配置文件") { openConfigFile() })
            addView(divider())
            addView(settingsRow(UiIcon.QR, "识别二维码", "相机扫描或识别相册图片") { showQrOptions() })
            addView(divider())
            addView(settingsRow(UiIcon.REFRESH, "更新当前订阅", AppState.profileName) { refreshProfile() })
            addView(divider())
            addView(settingsRow(UiIcon.EXPORT, "导出当前配置", "保存为 YAML 文件") { exportConfig() })
        })
        list.addView(sectionTitle("离线资源"))
        list.addView(card().apply {
            addView(settingsRow(
                UiIcon.NODES,
                "Geo 资源已内置",
                "${GeoResourceManager.existingCount()}/${GeoResourceManager.resources.size} 已就绪 · 点击检查或恢复",
            ) { restoreGeoResources() })
        })
        list.addView(sectionTitle("诊断日志"))
        val logSize = DiagnosticLog.sizeBytes()
        list.addView(card().apply {
            addView(settingsRow(UiIcon.LOG, "查看最近日志", "本地保存 · ${formatBytes(logSize)} / 2 MB") {
                showRecentLogs()
            })
            addView(divider())
            addView(settingsRow(UiIcon.EXPORT, "导出诊断日志", "排查无法连接的问题，订阅链接会脱敏") {
                exportDiagnosticLog()
            })
            addView(divider())
            addView(settingsRow(UiIcon.TRASH, "清空日志", "仅删除本机已收集的诊断记录", destructive = true) {
                confirmClearDiagnosticLog()
            })
        })
        list.addView(sectionTitle("系统"))
        list.addView(card().apply {
            addView(settingsRow(UiIcon.SETTINGS, "VPN 系统设置", "管理系统连接授权") {
                startActivity(Intent(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    Settings.ACTION_VPN_SETTINGS
                } else Settings.ACTION_SETTINGS))
            })
            addView(divider())
            addView(settingsRow(UiIcon.SHIELD, "简单，也尊重隐私", "无广告、无统计，日志只保存在本机"))
        })
        list.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(28), dp(12), dp(8))
            addView(iconTile(UiIcon.SHIELD, 48), square(48))
            addView(label("Clash Next", 17f, bold = true).apply {
                setPadding(0, dp(12), 0, dp(5))
            })
            addView(label("版本 ${appVersion()}", 12f, COLOR_MUTED))
            addView(label("Android arm64 · Mihomo", 11f, COLOR_MUTED).apply {
                setPadding(0, dp(5), 0, 0)
            })
        })
        return list.parent as View
    }

    @Suppress("DEPRECATION")
    private fun appVersion(): String = runCatching {
        packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()
    }.getOrDefault("").ifBlank { "未知" }

    private fun handleIncomingIntent(intent: Intent?) {
        val data = intent?.data
        val url = data?.getQueryParameter("url")
            ?: data?.getQueryParameter("config")
        if (!url.isNullOrBlank()) {
            downloadUrl(url)
        }
        if (ServiceController.shouldRequestVpn(intent)) {
            requestVpnPermission()
        }
    }

    private fun requestVpnPermission() {
        val prepare = VpnService.prepare(this)
        if (prepare == null) {
            ServiceController.startAfterPermission()
        } else {
            startActivityForResult(prepare, REQUEST_VPN)
        }
    }

    @Deprecated("Kept for Android 6 compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val scanResult = IntentIntegrator.parseActivityResult(requestCode, resultCode, data)
        if (scanResult != null) {
            scanResult.contents?.let(::handleQrText) ?: toast("未识别到二维码")
            return
        }
        when (requestCode) {
            REQUEST_VPN -> if (resultCode == RESULT_OK) {
                ServiceController.startAfterPermission()
            } else {
                toast("未授予 VPN 权限")
            }
            REQUEST_CONFIG_FILE -> if (resultCode == RESULT_OK) {
                data?.data?.let { importUri(it) }
            }
            REQUEST_EXPORT_CONFIG -> if (resultCode == RESULT_OK) {
                data?.data?.let { uri ->
                    runCatching {
                        contentResolver.openOutputStream(uri)?.use { output ->
                            ServiceController.configFile().inputStream().use { it.copyTo(output) }
                        }
                    }.onSuccess { toast("配置已导出") }
                        .onFailure { toast(it.message ?: "导出失败") }
                }
            }
            REQUEST_EXPORT_DIAGNOSTIC -> if (resultCode == RESULT_OK) {
                data?.data?.let { uri ->
                    runCatching { contentResolver.openOutputStream(uri) }
                        .onSuccess { output ->
                            if (output == null) {
                                toast("无法创建日志文件")
                            } else {
                                DiagnosticLog.record("APP", "diagnostic export requested")
                                DiagnosticLog.exportTo(output, diagnosticHeader()) { _, message ->
                                    toast(message)
                                }
                            }
                        }
                        .onFailure { toast(it.message ?: "日志导出失败") }
                }
            }
            REQUEST_GALLERY_QR -> if (resultCode == RESULT_OK) {
                data?.data?.let(::decodeGalleryQr)
            }
        }
    }

    private fun showUrlDialog() {
        val input = EditText(this).apply {
            hint = "https://example.com/subscription"
            setText(AppState.profileUrl)
            setSingleLine(true)
        }
        AlertDialog.Builder(this)
            .setTitle("导入订阅 URL")
            .setView(input)
            .setNegativeButton("取消", null)
            .setPositiveButton("下载") { _, _ -> downloadUrl(input.text.toString().trim()) }
            .show()
    }

    private fun showImportOptions() {
        AlertDialog.Builder(this)
            .setTitle("导入订阅")
            .setItems(arrayOf("粘贴订阅链接", "选择配置文件", "扫描二维码", "识别相册图片")) { _, index ->
                when (index) {
                    0 -> showUrlDialog()
                    1 -> openConfigFile()
                    2 -> scanCameraQr()
                    else -> openGalleryQr()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showQrOptions() {
        AlertDialog.Builder(this)
            .setTitle("识别二维码")
            .setItems(arrayOf("打开相机扫描", "从相册选择图片")) { _, index ->
                if (index == 0) scanCameraQr() else openGalleryQr()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun downloadUrl(url: String) {
        if (!url.startsWith("https://")) {
            toast("当前只接受 HTTPS 订阅地址")
            return
        }
        toast("正在下载订阅")
        ServiceController.downloadProfile(url) { success, message ->
            toast(message)
            if (success) showPage(0)
        }
    }

    private fun refreshProfile() {
        val url = AppState.profileUrl
        if (url.isBlank()) {
            toast("当前配置不是 URL 订阅")
        } else {
            downloadUrl(url)
        }
    }

    private fun openConfigFile() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }, REQUEST_CONFIG_FILE)
    }

    private fun importUri(uri: Uri) {
        Thread {
            runCatching {
                val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: error("无法读取文件")
                val label = uri.lastPathSegment?.substringAfterLast('/') ?: "本地配置"
                ServiceController.importConfig(bytes, label) { success, message ->
                    toast(message)
                    if (success) showPage(0)
                }
            }.onFailure { runOnUiThread { toast(it.message ?: "导入失败") } }
        }.start()
    }

    private fun exportConfig() {
        startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/x-yaml"
            putExtra(Intent.EXTRA_TITLE, "clash-next.yaml")
        }, REQUEST_EXPORT_CONFIG)
    }

    private fun exportDiagnosticLog() {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/plain"
            putExtra(Intent.EXTRA_TITLE, "clash-next-diagnostic-$stamp.log")
        }, REQUEST_EXPORT_DIAGNOSTIC)
    }

    private fun showRecentLogs() {
        DiagnosticLog.readRecent { value ->
            val textView = TextView(this).apply {
                text = value
                textSize = 11f
                typeface = Typeface.MONOSPACE
                setTextColor(COLOR_TEXT)
                setPadding(dp(16), dp(12), dp(16), dp(12))
                setTextIsSelectable(true)
            }
            AlertDialog.Builder(this)
                .setTitle("最近诊断日志")
                .setView(ScrollView(this).apply { addView(textView) })
                .setNegativeButton("关闭", null)
                .setPositiveButton("导出") { _, _ -> exportDiagnosticLog() }
                .show()
        }
    }

    private fun confirmClearDiagnosticLog() {
        AlertDialog.Builder(this)
            .setTitle("清空诊断日志？")
            .setMessage("已收集的核心和连接记录将被删除。")
            .setNegativeButton("取消", null)
            .setPositiveButton("清空") { _, _ ->
                DiagnosticLog.clear {
                    toast("诊断日志已清空")
                    if (page == 3) showPage(3)
                }
            }
            .show()
    }

    private fun diagnosticHeader(): String = buildString {
        appendLine("Generated: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date())}")
        appendLine("App: Clash Next ${appVersion()} (${packageName})")
        appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}; Android ${Build.VERSION.RELEASE}; SDK ${Build.VERSION.SDK_INT}")
        appendLine("Profile: ${AppState.profileName}")
        appendLine("Profile source: ${DiagnosticSanitizer.sourceHost(AppState.profileUrl)}")
        appendLine("VPN state: ${AppState.runState}; runtime=${AppState.runTime}")
        appendLine("Mode: ${AppState.mode}; node=${AppState.displayNode()}")
        appendLine("Bundled Geo: ${GeoResourceManager.existingCount()}/${GeoResourceManager.resources.size}")
    }

    private fun scanCameraQr() {
        IntentIntegrator(this)
            .setDesiredBarcodeFormats(IntentIntegrator.QR_CODE)
            .setPrompt("将二维码放入取景框")
            .setBeepEnabled(false)
            .setOrientationLocked(false)
            .initiateScan()
    }

    private fun openGalleryQr() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
        }, REQUEST_GALLERY_QR)
    }

    @Suppress("DEPRECATION")
    private fun decodeGalleryQr(uri: Uri) {
        Thread {
            runCatching {
                val bitmap = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                    ImageDecoder.decodeBitmap(ImageDecoder.createSource(contentResolver, uri)) { decoder, _, _ ->
                        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    }
                } else {
                    MediaStore.Images.Media.getBitmap(contentResolver, uri)
                }
                val pixels = IntArray(bitmap.width * bitmap.height)
                bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                val source = RGBLuminanceSource(bitmap.width, bitmap.height, pixels)
                MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(source))).text
            }.onSuccess { value -> runOnUiThread { handleQrText(value) } }
                .onFailure { runOnUiThread { toast("图片中没有识别到二维码") } }
        }.start()
    }

    private fun handleQrText(value: String) {
        val text = value.trim()
        val uri = runCatching { Uri.parse(text) }.getOrNull()
        val nestedUrl = uri?.getQueryParameter("url") ?: uri?.getQueryParameter("config")
        when {
            !nestedUrl.isNullOrBlank() -> downloadUrl(nestedUrl)
            text.startsWith("https://") -> downloadUrl(text)
            text.contains("proxies:") || text.contains("proxy-groups:") -> {
                ServiceController.importConfig(text.toByteArray(), "二维码配置") { success, message ->
                    toast(message)
                    if (success) showPage(0)
                }
            }
            else -> toast("二维码不是可识别的 Clash 订阅或 YAML")
        }
    }

    private fun restoreGeoResources() {
        toast("开始检查内置 Geo 资源")
        GeoResourceManager.restoreAll(
            onProgress = { name, index, total -> toast("$index/$total  $name") },
            onComplete = { _, message ->
                toast(message)
                if (page == 3) showPage(3)
            },
        )
    }

    private fun showJsonDetail(title: String, value: JSONObject) {
        val text = TextView(this).apply {
            this.text = value.toString(2)
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTextColor(COLOR_TEXT)
            setPadding(dp(18), dp(12), dp(18), dp(12))
            setTextIsSelectable(true)
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(ScrollView(this).apply { addView(text) })
            .setPositiveButton("关闭", null)
            .show()
    }

    private fun verticalScroll(): LinearLayout {
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(4), 0, dp(24))
        }
        ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            clipToPadding = false
            visibleScroll = this
            addView(list)
        }
        return list
    }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = outlined(colors.surface, 24f, colors.outline)
    }

    private fun infoCard(text: String): View = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        background = rounded(colors.surfaceSoft, 17f)
        setPadding(dp(14), dp(13), dp(14), dp(13))
        addView(iconView(UiIcon.INFO, COLOR_MUTED), square(18))
        addView(label(text, 12f, COLOR_MUTED).apply {
            setPadding(dp(9), 0, 0, 0)
            setLineSpacing(0f, 1.2f)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(8) }
    }

    private fun sectionTitle(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 13f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(COLOR_TEXT)
        setPadding(dp(4), dp(if (compactLayout) 16 else 21), dp(4), dp(10))
    }

    private fun primaryButton(label: String): Button = Button(this, null, 0).apply {
        text = label
        textSize = 14f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(colors.onPrimary)
        isAllCaps = false
        minimumHeight = dp(48)
        minHeight = dp(48)
        minimumWidth = 0
        minWidth = 0
        gravity = Gravity.CENTER
        setPadding(dp(16), 0, dp(16), 0)
        background = ripple(COLOR_PRIMARY, 17f)
    }

    private fun secondaryButton(label: String): Button = Button(this, null, 0).apply {
        text = label
        textSize = 13f
        setTextColor(COLOR_PRIMARY)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        isAllCaps = false
        minimumHeight = dp(48)
        minHeight = dp(48)
        minimumWidth = 0
        minWidth = 0
        gravity = Gravity.CENTER
        setPadding(dp(12), 0, dp(12), 0)
        background = ripple(colors.primarySoft, 16f)
    }

    private fun label(
        text: String,
        size: Float,
        color: Int = COLOR_TEXT,
        bold: Boolean = false,
    ) = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(color)
        includeFontPadding = false
        typeface = if (bold) Typeface.create("sans-serif-medium", Typeface.NORMAL) else Typeface.DEFAULT
        setLineSpacing(0f, 1.1f)
    }

    private fun pageHeading(title: String, subtitle: String) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(3), dp(if (compactLayout) 4 else 8), dp(3), dp(if (compactLayout) 14 else 21))
        addView(label(title, if (compactLayout) 22f else 25f, bold = true))
        if (!compactLayout) {
            addView(label(subtitle, 12f, COLOR_MUTED).apply { setPadding(0, dp(7), 0, 0) })
        }
    }

    private fun heroMetric(title: String, value: String) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(label(title, 10f, 0xFFB3C6E5.toInt()))
        addView(label(value, if (compactLayout) 13f else 15f, Color.WHITE, true).apply {
            setPadding(0, dp(7), dp(if (compactLayout) 8 else 4), 0)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            fontFeatureSettings = "tnum"
        })
    }

    private fun iconView(icon: UiIcon, color: Int) = ImageView(this).apply {
        setImageDrawable(AppIconDrawable(icon, color))
        scaleType = ImageView.ScaleType.FIT_CENTER
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private fun iconTile(icon: UiIcon, size: Int) = FrameLayout(this).apply {
        background = rounded(colors.primarySoft, if (size > 42) 16f else 13f)
        addView(iconView(icon, COLOR_PRIMARY), FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
    }

    private fun iconAction(
        icon: UiIcon,
        description: String,
        rotated: Boolean = false,
        action: () -> Unit,
    ) = FrameLayout(this).apply {
        background = ripple(Color.TRANSPARENT, 16f)
        addView(iconView(icon, COLOR_MUTED).apply {
            if (rotated) rotation = 45f
        }, FrameLayout.LayoutParams(dp(21), dp(21), Gravity.CENTER))
        minimumWidth = dp(48)
        minimumHeight = dp(48)
        contentDescription = description
        isFocusable = true
        setOnClickListener { action() }
    }

    private fun settingsRow(
        icon: UiIcon,
        title: String,
        subtitle: String = "",
        destructive: Boolean = false,
        action: (() -> Unit)? = null,
    ) = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(72)
        setPadding(dp(15), dp(13), dp(13), dp(13))
        background = ripple(colors.surface, 22f)
        addView(iconTile(icon, 38), square(38))
        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, dp(8), 0)
            addView(label(title, 14f, if (destructive) colors.error else COLOR_TEXT, true).apply {
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            })
            if (subtitle.isNotBlank()) addView(label(subtitle, 11f, COLOR_MUTED).apply {
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                setPadding(0, dp(5), 0, 0)
            })
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (action != null) {
            addView(iconView(UiIcon.CHEVRON, COLOR_MUTED), square(16))
            isFocusable = true
            contentDescription = if (subtitle.isBlank()) title else "$title，$subtitle"
            setOnClickListener { action() }
        }
    }

    private fun quickTile(icon: UiIcon, title: String, subtitle: String, action: () -> Unit) =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            minimumHeight = dp(112)
            setPadding(dp(16), dp(15), dp(12), dp(15))
            background = ripple(colors.surface, 22f)
            addView(iconView(icon, COLOR_PRIMARY), square(23))
            addView(label(title, 14f, bold = true).apply { setPadding(0, dp(11), 0, 0) })
            addView(label(subtitle, 10f, COLOR_MUTED).apply { setPadding(0, dp(5), 0, 0) })
            isFocusable = true
            setOnClickListener { action() }
        }

    private fun emptyState(icon: UiIcon, title: String, description: String) = card().apply {
        gravity = Gravity.CENTER
        setPadding(dp(22), dp(28), dp(22), dp(28))
        addView(iconTile(icon, 54), square(54))
        addView(label(title, 16f, bold = true).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(16), 0, dp(8))
        })
        addView(label(description, 12f, COLOR_MUTED).apply {
            gravity = Gravity.CENTER
            setLineSpacing(0f, 1.25f)
        })
    }

    private fun footer() = label("Clash Next · ${appVersion()}", 10f, COLOR_MUTED).apply {
        gravity = Gravity.CENTER
        setPadding(0, dp(22), 0, dp(4))
    }

    private fun setButtonIcon(button: Button, icon: UiIcon, color: Int) {
        val drawable = AppIconDrawable(icon, color).apply { setBounds(0, 0, dp(19), dp(19)) }
        button.setCompoundDrawablesRelative(drawable, null, null, null)
        button.compoundDrawablePadding = dp(8)
    }

    private fun rowButton(label: String, action: () -> Unit): View =
        settingsRow(UiIcon.REFRESH, label, action = action)

    private fun divider(): View = View(this).apply {
        setBackgroundColor(colors.outline)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
            marginStart = dp(65)
            marginEnd = dp(16)
        }
    }

    private fun rounded(color: Int, radiusDp: Float): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp.toInt()).toFloat()
    }

    private fun outlined(color: Int, radiusDp: Float, stroke: Int): GradientDrawable =
        rounded(color, radiusDp).apply { setStroke(dp(1), stroke) }

    private fun ripple(color: Int, radiusDp: Float): RippleDrawable = RippleDrawable(
        ColorStateList.valueOf(if (nightMode) 0x22FFFFFF else 0x223F64E9),
        rounded(color, radiusDp),
        rounded(Color.WHITE, radiusDp),
    )

    private fun square(size: Int) = LinearLayout.LayoutParams(dp(size), dp(size))

    private fun matchHeight(heightDp: Int) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        dp(heightDp),
    )

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun formatBytes(value: Long): String {
        val units = arrayOf("B", "KB", "MB", "GB")
        var number = value.toDouble()
        var index = 0
        while (number >= 1024 && index < units.lastIndex) {
            number /= 1024
            index++
        }
        return if (index == 0) "${number.toLong()} ${units[index]}" else "%.1f %s".format(number, units[index])
    }

    private fun toast(message: String) {
        if (message.isNotBlank()) Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
