package com.srisomesh.assistant

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Restarts listening after a reboot or an app update, if it was on before. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (Prefs(context).listeningWanted && WakeEngine.isDownloaded(context)) {
            ListenService.start(context)
        }
    }
}
