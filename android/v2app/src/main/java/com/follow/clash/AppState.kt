package com.follow.clash

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

enum class RunState {
    STOPPED,
    STARTING,
    RUNNING,
    ERROR,
}

object AppState {
    private const val FILE = "clash_next_state"
    private const val KEY_RUN_STATE = "run_state"
    private const val KEY_PROFILE_NAME = "profile_name"
    private const val KEY_PROFILE_URL = "profile_url"
    private const val KEY_LAST_ERROR = "last_error"
    private const val KEY_RUN_TIME = "run_time"
    private const val KEY_MODE = "mode"
    private const val KEY_WIDGET_GROUP = "widget_group"
    private const val KEY_SELECTED_MAP = "selected_map"
    private const val KEY_SELECTED_NODE = "selected_node"
    private const val KEY_WIDGET_NODE = "widget_node"
    private const val KEY_WIDGET_NODE_FLAG = "widget_node_flag"
    private const val KEY_WIDGET_EXIT_IP = "widget_exit_ip"
    private const val KEY_WIDGET_EXIT_COUNTRY = "widget_exit_country"
    private const val KEY_WIDGET_EXIT_REFRESH = "widget_exit_refresh"
    private const val KEY_WIDGET_UP = "widget_up"
    private const val KEY_WIDGET_DOWN = "widget_down"

    private lateinit var preferences: SharedPreferences

    fun init(context: Context) {
        if (!::preferences.isInitialized) {
            preferences = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        }
    }

    var runState: RunState
        get() = runCatching {
            RunState.valueOf(preferences.getString(KEY_RUN_STATE, RunState.STOPPED.name)!!)
        }.getOrDefault(RunState.STOPPED)
        set(value) {
            preferences.edit().putString(KEY_RUN_STATE, value.name).apply()
        }

    var profileName: String
        get() = preferences.getString(KEY_PROFILE_NAME, "内置直连") ?: "内置直连"
        set(value) {
            preferences.edit().putString(KEY_PROFILE_NAME, value).apply()
        }

    var profileUrl: String
        get() = preferences.getString(KEY_PROFILE_URL, "").orEmpty()
        set(value) {
            preferences.edit().putString(KEY_PROFILE_URL, value).apply()
        }

    var lastError: String
        get() = preferences.getString(KEY_LAST_ERROR, "").orEmpty()
        set(value) {
            preferences.edit().putString(KEY_LAST_ERROR, value).apply()
        }

    var runTime: Long
        get() = preferences.getLong(KEY_RUN_TIME, 0L)
        set(value) {
            preferences.edit().putLong(KEY_RUN_TIME, value).apply()
        }

    var mode: String
        get() = preferences.getString(KEY_MODE, "rule") ?: "rule"
        set(value) {
            preferences.edit().putString(KEY_MODE, value).apply()
        }

    var widgetGroup: String
        get() = preferences.getString(KEY_WIDGET_GROUP, "").orEmpty()
        set(value) {
            preferences.edit().putString(KEY_WIDGET_GROUP, value).apply()
        }

    var selectedMap: Map<String, String>
        get() {
            val stored = preferences.getString(KEY_SELECTED_MAP, "").orEmpty()
            val result = linkedMapOf<String, String>()
            runCatching {
                val json = JSONObject(stored.ifBlank { "{}" })
                val keys = json.keys()
                while (keys.hasNext()) {
                    val group = keys.next()
                    json.optString(group).takeIf { it.isNotBlank() }?.let {
                        result[group] = it
                    }
                }
            }
            if (result.isEmpty()) {
                val group = widgetGroup
                val node = widgetNode.takeUnless { it == "等待节点信息" }.orEmpty()
                if (group.isNotBlank() && node.isNotBlank()) result[group] = node
            }
            return result
        }
        set(value) {
            val json = JSONObject()
            for ((group, node) in value) {
                if (group.isNotBlank() && node.isNotBlank()) json.put(group, node)
            }
            preferences.edit().putString(KEY_SELECTED_MAP, json.toString()).apply()
        }

    var selectedNode: String
        get() = preferences.getString(KEY_SELECTED_NODE, "").orEmpty()
            .ifBlank {
                selectedMap[widgetGroup]
                    ?: widgetNode.takeUnless { it == "等待节点信息" }
                    ?: ""
            }
        set(value) {
            preferences.edit().putString(KEY_SELECTED_NODE, value).apply()
        }

    var widgetNode: String
        get() = preferences.getString(KEY_WIDGET_NODE, "等待节点信息").orEmpty()
        set(value) {
            preferences.edit().putString(KEY_WIDGET_NODE, value).apply()
        }

    var widgetNodeFlag: String
        get() = preferences.getString(KEY_WIDGET_NODE_FLAG, "🌐") ?: "🌐"
        set(value) {
            preferences.edit().putString(KEY_WIDGET_NODE_FLAG, value).apply()
        }

    var widgetExitIp: String
        get() = preferences.getString(KEY_WIDGET_EXIT_IP, "正在获取出口 IP").orEmpty()
        set(value) {
            preferences.edit().putString(KEY_WIDGET_EXIT_IP, value).apply()
        }

    var widgetExitCountry: String
        get() = preferences.getString(KEY_WIDGET_EXIT_COUNTRY, "").orEmpty()
        set(value) {
            preferences.edit().putString(KEY_WIDGET_EXIT_COUNTRY, value).apply()
        }

    var widgetExitRefreshTime: Long
        get() = preferences.getLong(KEY_WIDGET_EXIT_REFRESH, 0L)
        set(value) {
            preferences.edit().putLong(KEY_WIDGET_EXIT_REFRESH, value).apply()
        }

    var widgetUp: Long
        get() = preferences.getLong(KEY_WIDGET_UP, 0L)
        set(value) {
            preferences.edit().putLong(KEY_WIDGET_UP, value).apply()
        }

    var widgetDown: Long
        get() = preferences.getLong(KEY_WIDGET_DOWN, 0L)
        set(value) {
            preferences.edit().putLong(KEY_WIDGET_DOWN, value).apply()
        }

    fun rememberSelection(group: String, node: String) {
        if (group.isBlank() || node.isBlank()) return
        selectedMap = selectedMap.toMutableMap().apply { put(group, node) }
        widgetGroup = group
        selectedNode = node
    }

    fun displayNode(state: RunState = runState): String {
        return WidgetInfo.displayNode(
            running = state == RunState.RUNNING,
            activeNode = widgetNode,
            selectedNode = selectedNode,
        )
    }
}
