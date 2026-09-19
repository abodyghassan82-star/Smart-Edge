package com.imi.smartedge.sidebar.panel

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast

/**
 * Debug receiver for dock-test notification actions.
 * All logic lives in DockTestReceiverHelper; this just bridges intents → helper.
 */
class DockTestReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_SHRINK =
            "com.abody.smartedgedock.action.DOCK_TEST_SHRINK"
        const val ACTION_RESTORE =
            "com.abody.smartedgedock.action.DOCK_TEST_RESTORE"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent == null) return
        val log = StringBuilder()
        val summary = when (intent.action) {
            ACTION_SHRINK  -> DockTestHelper.shrink(context, log)
            ACTION_RESTORE -> DockTestHelper.restore(context, log)
            else -> return
        }
        Log.d("DockTest", log.toString())
        Toast.makeText(context, summary, Toast.LENGTH_LONG).show()
    }
}
