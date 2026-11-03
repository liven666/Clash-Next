package com.follow.clash

import android.annotation.TargetApi
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService as AndroidTileService
import com.follow.clash.common.QuickAction
import com.follow.clash.common.quickIntent
import com.follow.clash.common.toPendingIntent

@TargetApi(Build.VERSION_CODES.N)
class TileService : AndroidTileService(), ServiceController.Listener {
    override fun onStartListening() {
        super.onStartListening()
        ServiceController.init(application)
        ServiceController.addListener(this)
        ServiceController.syncState()
        updateTile(AppState.runState)
    }

    override fun onStopListening() {
        ServiceController.removeListener(this)
        super.onStopListening()
    }

    override fun onStateChanged(state: RunState, message: String) {
        updateTile(state)
    }

    private fun updateTile(state: RunState) {
        val tile = qsTile ?: return
        tile.state = when (state) {
            RunState.RUNNING -> Tile.STATE_ACTIVE
            RunState.STARTING -> Tile.STATE_UNAVAILABLE
            RunState.STOPPED, RunState.ERROR -> Tile.STATE_INACTIVE
        }
        tile.label = getString(R.string.tile_name)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = when (state) {
                RunState.RUNNING -> getString(R.string.widget_status_on)
                RunState.STARTING -> getString(R.string.widget_status_pending)
                RunState.STOPPED, RunState.ERROR -> getString(R.string.widget_status_off)
            }
        }
        tile.updateTile()
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    override fun onClick() {
        super.onClick()
        val intent = QuickAction.TOGGLE.quickIntent
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(intent.toPendingIntent)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    companion object {
        fun requestRefresh(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                AndroidTileService.requestListeningState(
                    context,
                    ComponentName(context, TileService::class.java),
                )
            }
        }
    }
}
