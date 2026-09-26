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
        val action = intent?.action ?: return
        if (action != ACTION_SHRINK && action != ACTION_RESTORE) return
        val app = context.applicationContext
        // Background: shrink/restore verify the resize (sleeps + dumpsys) —
        // never run that on the main thread of a BroadcastReceiver.
        Thread {
            val log = StringBuilder()
            val summary = when (action) {
                ACTION_SHRINK  -> DockTestHelper.shrink(app, log)
                else           -> DockTestHelper.restore(app, log)
            }
            Log.d("DockTest", log.toString())
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                Toast.makeText(app, summary, Toast.LENGTH_LONG).show()
            }
        }.start()
    }
}
