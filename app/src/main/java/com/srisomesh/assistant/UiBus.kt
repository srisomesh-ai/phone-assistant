package com.srisomesh.assistant

import android.os.Handler
import android.os.Looper

/** Tiny in-app message bus so screens can show what the assistant is doing. */
object UiBus {
    private val main = Handler(Looper.getMainLooper())
    private val listeners = mutableSetOf<(String, String) -> Unit>()

    /** Last state shown on screen: status line + main text. */
    var status = ""
        private set
    var text = ""
        private set

    fun post(newStatus: String, newText: String? = null) {
        main.post {
            status = newStatus
            if (newText != null) text = newText
            listeners.toList().forEach { it(status, text) }
        }
    }

    fun add(l: (String, String) -> Unit) { listeners.add(l) }
    fun remove(l: (String, String) -> Unit) { listeners.remove(l) }
}
