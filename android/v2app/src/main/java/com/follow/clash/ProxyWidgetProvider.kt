package com.follow.clash

import android.annotation.TargetApi
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.SizeF
import android.view.View
import android.widget.RemoteViews
import com.follow.clash.common.QuickAction
import com.follow.clash.common.quickIntent
import com.follow.clash.common.toPendingIntent

class ProxyWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        updateWidgets(context, appWidgetManager, appWidgetIds, AppState.runState)
        ServiceController.init(context.applicationContext as ClashNextApplication)
        ServiceController.syncState()
        ServiceController.refreshWidgetData()
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle,
    ) {
        super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions)
        updateWidget(context, appWidgetManager, appWidgetId, AppState.runState, newOptions)
    }

    companion object {
        private const val COMPACT_WIDTH = 110
        private const val COMPACT_HEIGHT = 110
        private const val WIDE_THRESHOLD = 180
        private const val TALL_THRESHOLD = 80

        fun updateAll(context: Context, runState: RunState) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, ProxyWidgetProvider::class.java))
            updateWidgets(context, manager, ids, runState)
        }

        private fun updateWidgets(
            context: Context,
            manager: AppWidgetManager,
            ids: IntArray,
            state: RunState,
        ) {
            ids.forEach { id ->
                updateWidget(context, manager, id, state, manager.getAppWidgetOptions(id))
            }
        }

        private fun updateWidget(
            context: Context,
            manager: AppWidgetManager,
            id: Int,
            state: RunState,
            options: Bundle,
        ) {
            runCatching {
                val views = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    createExactRemoteViews(context, options, state)
                } else null) ?: createLegacyRemoteViews(context, options, state)
                manager.updateAppWidget(id, views)
            }
        }

        @TargetApi(Build.VERSION_CODES.S)
        @Suppress("DEPRECATION")
        private fun createExactRemoteViews(
            context: Context,
            options: Bundle,
            state: RunState,
        ): RemoteViews? {
            return runCatching {
                val sizes = options.getParcelableArrayList<SizeF>(
                    AppWidgetManager.OPTION_APPWIDGET_SIZES,
                )
                if (sizes.isNullOrEmpty()) return null
                val layouts = linkedMapOf<SizeF, RemoteViews>()
                sizes.take(16).forEach { size ->
                    if (size.width > 0 && size.height > 0) {
                        layouts[size] = createRemoteViews(
                            context,
                            selectLayout(size.width.toInt(), size.height.toInt()),
                            state,
                        )
                    }
                }
                if (layouts.isEmpty()) null else RemoteViews(layouts)
            }.getOrNull()
        }

        private fun createLegacyRemoteViews(
            context: Context,
            options: Bundle,
            state: RunState,
        ): RemoteViews {
            val minWidth = option(options, AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, COMPACT_WIDTH)
            val maxWidth = option(options, AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, minWidth)
            val minHeight = option(options, AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, COMPACT_HEIGHT)
            val maxHeight = option(options, AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, minHeight)
            return RemoteViews(
                createRemoteViews(context, selectLayout(maxWidth, minHeight), state),
                createRemoteViews(context, selectLayout(minWidth, maxHeight), state),
            )
        }

        private fun option(options: Bundle, key: String, fallback: Int): Int {
            return options.getInt(key, fallback).takeIf { it > 0 } ?: fallback
        }

        internal fun selectLayout(width: Int, height: Int): Int {
            val wide = width >= WIDE_THRESHOLD
            val tall = height >= TALL_THRESHOLD
            return when {
                wide && tall -> R.layout.widget_2x4
                wide -> R.layout.widget_1x4
                tall -> R.layout.widget_2x2
                else -> R.layout.widget_1x2
            }
        }

        private fun createRemoteViews(
            context: Context,
            layoutId: Int,
            state: RunState,
        ): RemoteViews {
            val status = when (state) {
                RunState.RUNNING -> R.string.widget_status_on
                RunState.STARTING -> R.string.widget_status_pending
                RunState.STOPPED, RunState.ERROR -> R.string.widget_status_off
            }
            val background = when (state) {
                RunState.RUNNING -> R.drawable.widget_background_on
                RunState.STARTING -> R.drawable.widget_background_pending
                RunState.STOPPED, RunState.ERROR -> R.drawable.widget_background_off
            }
            return RemoteViews(context.packageName, layoutId).apply {
                setTextViewText(R.id.widget_title, context.getString(R.string.app_name))
                setTextViewText(R.id.widget_status, context.getString(status))
                val displayNode = AppState.displayNode(state)
                setTextViewText(R.id.widget_node_flag, WidgetInfo.flagFromNodeName(displayNode))
                setTextViewText(R.id.widget_node, displayNode)
                setTextViewText(
                    R.id.widget_ip_flag,
                    WidgetInfo.flagFromCountryCode(AppState.widgetExitCountry),
                )
                setTextViewText(
                    R.id.widget_exit_ip,
                    if (state == RunState.RUNNING) AppState.widgetExitIp else "出口 IP --",
                )
                setTextViewText(
                    R.id.widget_speed,
                    WidgetInfo.speedText(AppState.widgetUp, AppState.widgetDown),
                )
                if (state == RunState.RUNNING && AppState.runTime > 0L) {
                    val elapsed = (System.currentTimeMillis() - AppState.runTime).coerceAtLeast(0L)
                    val base = SystemClock.elapsedRealtime() - elapsed
                    setChronometer(R.id.widget_duration, base, "连接 %s", true)
                } else {
                    // Some launchers reuse the running Chronometer between updates.
                    setChronometer(
                        R.id.widget_duration,
                        SystemClock.elapsedRealtime(),
                        null,
                        false,
                    )
                    setTextViewText(
                        R.id.widget_duration,
                        if (state == RunState.STARTING) "正在处理" else "未连接",
                    )
                }
                setInt(android.R.id.background, "setBackgroundResource", background)
                setViewVisibility(
                    R.id.widget_power,
                    if (state == RunState.STARTING) View.GONE else View.VISIBLE,
                )
                setViewVisibility(
                    R.id.widget_progress,
                    if (state == RunState.STARTING) View.VISIBLE else View.GONE,
                )
                val action = QuickAction.TOGGLE.quickIntent.toPendingIntent
                setOnClickPendingIntent(R.id.widget_power_container, action)
                val openApp = PendingIntent.getActivity(
                    context,
                    2201,
                    Intent(context, MainActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    },
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
                setOnClickPendingIntent(android.R.id.background, openApp)
            }
        }
    }
}
