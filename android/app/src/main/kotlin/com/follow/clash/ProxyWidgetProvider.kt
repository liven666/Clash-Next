package com.follow.clash

import android.annotation.TargetApi
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.util.SizeF
import android.view.View
import android.widget.RemoteViews
import com.follow.clash.common.GlobalState
import com.follow.clash.common.QuickAction
import com.follow.clash.common.quickIntent
import com.follow.clash.common.toPendingIntent
import kotlinx.coroutines.launch

class ProxyWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        updateWidgets(context, appWidgetManager, appWidgetIds, State.runStateFlow.value)
        syncState()
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle,
    ) {
        super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions)
        updateWidget(context, appWidgetManager, appWidgetId, State.runStateFlow.value, newOptions)
    }

    private fun syncState() {
        val pendingResult = goAsync()
        GlobalState.launch {
            try {
                State.handleSyncState()
            } finally {
                updateAll(GlobalState.application, State.runStateFlow.value)
                pendingResult.finish()
            }
        }
    }

    companion object {
        private const val COMPACT_WIDTH = 110
        private const val COMPACT_HEIGHT = 110
        private const val WIDE_THRESHOLD = 180
        private const val TALL_THRESHOLD = 80

        fun updateAll(context: Context, runState: RunState) {
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val componentName = ComponentName(context, ProxyWidgetProvider::class.java)
            val appWidgetIds = appWidgetManager.getAppWidgetIds(componentName)
            updateWidgets(context, appWidgetManager, appWidgetIds, runState)
        }

        private fun updateWidgets(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetIds: IntArray,
            runState: RunState,
        ) {
            appWidgetIds.forEach { appWidgetId ->
                updateWidget(
                    context,
                    appWidgetManager,
                    appWidgetId,
                    runState,
                    appWidgetManager.getAppWidgetOptions(appWidgetId),
                )
            }
        }

        private fun updateWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int,
            runState: RunState,
            options: Bundle,
        ) {
            try {
                val remoteViews = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    createExactRemoteViews(context, options, runState)
                } else {
                    null
                } ?: createLegacyRemoteViews(context, options, runState)
                appWidgetManager.updateAppWidget(appWidgetId, remoteViews)
            } catch (_: RuntimeException) {
                // A broken OEM launcher instance must not block the remaining widgets.
            }
        }

        @TargetApi(Build.VERSION_CODES.S)
        @Suppress("DEPRECATION")
        private fun createExactRemoteViews(
            context: Context,
            options: Bundle,
            runState: RunState,
        ): RemoteViews? {
            return try {
                val sizes = options.getParcelableArrayList<SizeF>(
                    AppWidgetManager.OPTION_APPWIDGET_SIZES,
                )
                if (sizes.isNullOrEmpty()) return null
                val layouts = linkedMapOf<SizeF, RemoteViews>()
                sizes.take(16).forEach { size ->
                    if (size.width > 0f && size.height > 0f) {
                        layouts[size] = createRemoteViews(
                            context,
                            selectLayout(size.width.toInt(), size.height.toInt()),
                            runState,
                        )
                    }
                }
                if (layouts.isEmpty()) null else RemoteViews(layouts)
            } catch (_: RuntimeException) {
                null
            }
        }

        private fun createLegacyRemoteViews(
            context: Context,
            options: Bundle,
            runState: RunState,
        ): RemoteViews {
            val minWidth = option(options, AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, COMPACT_WIDTH)
            val maxWidth = option(options, AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, minWidth)
            val minHeight = option(options, AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, COMPACT_HEIGHT)
            val maxHeight = option(options, AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, minHeight)
            val landscape = createRemoteViews(
                context,
                selectLayout(maxWidth, minHeight),
                runState,
            )
            val portrait = createRemoteViews(
                context,
                selectLayout(minWidth, maxHeight),
                runState,
            )
            return RemoteViews(landscape, portrait)
        }

        private fun option(options: Bundle, key: String, fallback: Int): Int {
            val value = options.getInt(key, fallback)
            return if (value > 0) value else fallback
        }

        internal fun selectLayout(width: Int, height: Int): Int {
            val isWide = width >= WIDE_THRESHOLD
            val isTall = height >= TALL_THRESHOLD
            return when {
                isWide && isTall -> R.layout.widget_2x4
                isWide -> R.layout.widget_1x4
                isTall -> R.layout.widget_2x2
                else -> R.layout.widget_1x2
            }
        }

        private fun createRemoteViews(
            context: Context,
            layoutId: Int,
            runState: RunState,
        ): RemoteViews {
            val statusId: Int
            val backgroundId: Int
            when (runState) {
                RunState.START -> {
                    statusId = R.string.widget_status_on
                    backgroundId = R.drawable.widget_background_on
                }

                RunState.PENDING -> {
                    statusId = R.string.widget_status_pending
                    backgroundId = R.drawable.widget_background_pending
                }

                RunState.STOP -> {
                    statusId = R.string.widget_status_off
                    backgroundId = R.drawable.widget_background_off
                }
            }

            val status = context.getString(statusId)
            val hint = context.getString(R.string.widget_tap_to_toggle)
            val remoteViews = RemoteViews(context.packageName, layoutId).apply {
                setTextViewText(R.id.widget_title, context.getString(R.string.app_name))
                setTextViewText(R.id.widget_status, status)
                setTextViewText(R.id.widget_hint, hint)
                setInt(android.R.id.background, "setBackgroundResource", backgroundId)
                setViewVisibility(
                    R.id.widget_power,
                    if (runState == RunState.PENDING) View.GONE else View.VISIBLE,
                )
                setViewVisibility(
                    R.id.widget_progress,
                    if (runState == RunState.PENDING) View.VISIBLE else View.GONE,
                )
                setContentDescription(
                    android.R.id.background,
                    context.getString(R.string.widget_content_description, status, hint),
                )
                setOnClickPendingIntent(
                    android.R.id.background,
                    QuickAction.TOGGLE.quickIntent.toPendingIntent,
                )
                setOnClickPendingIntent(
                    R.id.widget_power_container,
                    QuickAction.TOGGLE.quickIntent.toPendingIntent,
                )
            }
            return remoteViews
        }
    }
}
