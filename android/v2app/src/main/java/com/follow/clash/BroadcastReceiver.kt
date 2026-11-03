package com.follow.clash

import android.content.Context
import android.content.Intent
import com.follow.clash.common.BroadcastAction
import com.follow.clash.common.action

class BroadcastReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        val app = context?.applicationContext as? ClashNextApplication ?: return
        ServiceController.init(app)
        when (intent?.action) {
            BroadcastAction.SERVICE_CREATED.action -> ServiceController.syncState()
            BroadcastAction.SERVICE_DESTROYED.action -> {
                AppState.runState = RunState.STOPPED
                AppState.runTime = 0L
                ProxyWidgetProvider.updateAll(app, RunState.STOPPED)
                TileService.requestRefresh(app)
            }
        }
    }
}
