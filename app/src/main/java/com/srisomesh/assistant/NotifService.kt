package com.srisomesh.assistant

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/** Keeps the latest notifications so the assistant can read them out ("read my messages"). */
class NotifService : NotificationListenerService() {

    data class Item(val app: String, val title: String, val text: String, val time: Long)

    companion object {
        private val items = ArrayDeque<Item>()

        @Synchronized
        fun recent(): List<Item> = items.toList()

        @Synchronized
        fun clear() = items.clear()

        @Synchronized
        private fun add(item: Item) {
            items.removeAll { it.app == item.app && it.title == item.title && it.text == item.text }
            items.addFirst(item)
            while (items.size > 15) items.removeLast()
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        if (sbn.isOngoing || sbn.packageName == packageName) return
        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        if (title.isBlank() && text.isBlank()) return
        if (sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val app = try {
            val ai = packageManager.getApplicationInfo(sbn.packageName, 0)
            packageManager.getApplicationLabel(ai).toString()
        } catch (e: Exception) { sbn.packageName }
        add(Item(app, title, text, sbn.postTime))
    }
}
