package com.follow.clash

import android.app.Application
import android.app.ActivityManager
import android.content.Context
import com.follow.clash.common.GlobalState

class ClashNextApplication : Application() {
    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)
        GlobalState.init(this)
    }

    override fun onCreate() {
        super.onCreate()
        GlobalState.init(this)
        AppState.init(this)
        DiagnosticLog.init(this)
        if (!isRemoteProcess()) {
            ServiceController.init(this)
        }
    }

    private fun isRemoteProcess(): Boolean {
        val manager = getSystemService(ActivityManager::class.java) ?: return false
        val process = manager.runningAppProcesses?.firstOrNull {
            it.pid == android.os.Process.myPid()
        }?.processName.orEmpty()
        return process.endsWith(":remote")
    }
}
