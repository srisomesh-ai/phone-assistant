package com.srisomesh.assistant

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

/**
 * The assistant's "hands" inside apps:
 *  - presses WhatsApp's send button after a voice message has been prepared
 *  - locks the phone on command ("lock the phone")
 */
class ControlService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: ControlService? = null
            private set
    }

    private var waSendDeadline = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onInterrupt() {}

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (System.currentTimeMillis() < waSendDeadline && event?.packageName == "com.whatsapp") {
            tryWhatsAppSend()
        }
    }

    /** Next time WhatsApp's chat opens (within 15 s), press its send button once. */
    fun armWhatsAppSend() {
        waSendDeadline = System.currentTimeMillis() + 15_000
    }

    private fun tryWhatsAppSend() {
        val root = rootInActiveWindow ?: return
        val buttons = root.findAccessibilityNodeInfosByViewId("com.whatsapp:id/send")
        val btn = buttons.firstOrNull { it.isVisibleToUser && it.isEnabled } ?: return
        if (btn.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)) {
            waSendDeadline = 0
        }
    }

    fun lockPhone(): Boolean = performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
}
