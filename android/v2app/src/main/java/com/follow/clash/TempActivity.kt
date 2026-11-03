package com.follow.clash

import android.app.Activity
import android.os.Bundle
import com.follow.clash.common.QuickAction
import com.follow.clash.common.action

class TempActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ServiceController.init(application)
        when (intent?.action) {
            QuickAction.START.action -> ServiceController.requestStartWithPermission()
            QuickAction.STOP.action -> ServiceController.stop()
            QuickAction.TOGGLE.action -> ServiceController.requestToggle()
        }
        finish()
    }
}
