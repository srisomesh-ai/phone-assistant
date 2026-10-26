package com.srisomesh.assistant

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.MediaStore
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Understands a spoken command and does it. Replies with a short line to speak back.
 * Works with Telugu and English keywords. Anything it doesn't recognise goes to the on-phone AI.
 */
class CommandRouter(private val ctx: Context) {

    fun handle(text: String, reply: (String) -> Unit) {
        val t = text.lowercase().trim()

        // --- stop / cancel ---
        if (anyOf(t, "stop", "cancel", "quiet", "chaalu", "ఆపు", "చాలు")) {
            reply(""); return
        }

        // --- time / date / battery : work on the lock screen, no unlock ---
        if (anyOf(t, "time", "samayam", "సమయం", "టైం", "what time")) {
            reply(timeString()); return
        }
        if (anyOf(t, "date", "today", "ee roju", "రోజు", "తేదీ")) {
            reply(dateString()); return
        }
        if (anyOf(t, "battery", "charge", "ఛార్జ్", "బ్యాటరీ")) {
            val pct = Unlocker.batteryPercent(ctx)
            val chg = if (Unlocker.isCharging(ctx)) ", charging" else ""
            reply("Battery is $pct percent$chg."); return
        }

        // --- flashlight ---
        if (anyOf(t, "flashlight", "torch", "light on", "light off", "టార్చ్", "లైట్")) {
            val on = !anyOf(t, "off", "ఆపు", "aapu")
            val ok = Torch.set(ctx, on)
            reply(if (ok) (if (on) "Torch on." else "Torch off.") else "Couldn't control the torch."); return
        }

        // --- alarms & timers (system handles these on the lock screen) ---
        extractMinutes(t)?.let { mins ->
            if (anyOf(t, "timer", "టైమర్")) {
                startTimer(mins * 60, reply); return
            }
        }
        if (anyOf(t, "alarm", "wake me", "అలారం", "మేల్కొలుపు")) {
            extractClock(t)?.let { (h, m) -> setAlarm(h, m, reply); return }
        }

        // --- read my notifications / messages ---
        if (anyOf(t, "notification", "messages", "my message", "ఏమైనా మెసేజ్", "మెసేజెస్", "సందేశాలు", "what's new")) {
            reply(readNotifications()); return
        }

        // --- call someone ---
        if (anyOf(t, "call ", "phone ", "dial ", "కాల్", "ఫోన్ చెయ్యి")) {
            val name = afterAny(t, listOf("call", "phone", "dial", "కాల్", "ఫోన్")).trim()
            if (name.isNotBlank()) { callContact(name, reply); return }
        }

        // --- send a WhatsApp message (dictated) ---
        whatsappTarget(t)?.let { (name, msg) ->
            sendWhatsApp(name, msg, reply); return
        }

        // --- open an app or do a web search ---
        if (anyOf(t, "open ", "launch ", "తెరువు", "ఓపెన్")) {
            val what = afterAny(t, listOf("open", "launch", "తెరువు", "ఓపెన్")).trim()
            if (what.isNotBlank()) { openApp(what, reply); return }
        }
        if (anyOf(t, "camera", "selfie", "కెమెరా", "సెల్ఫీ")) {
            openCamera(anyOf(t, "selfie", "front", "సెల్ఫీ"), reply); return
        }
        if (anyOf(t, "search ", "youtube ", "play ", "సెర్చ్", "వెతుకు")) {
            handleSearch(t, reply); return
        }
        if (anyOf(t, "lock the phone", "lock phone", "లాక్ చెయ్యి", "ఫోన్ లాక్")) {
            needOpen(reply) {
                val ok = ControlService.instance?.lockPhone() == true
                reply(if (ok) "Locked." else "Turn on accessibility control to lock the phone.")
            }
            return
        }

        // --- anything else → on-phone AI ---
        Thread {
            val answer = LocalAi.ask(ctx, text)
            reply(answer)
        }.start()
    }

    // ---------------- helpers ----------------

    private fun anyOf(t: String, vararg keys: String) = keys.any { t.contains(it) }

    private fun afterAny(t: String, keys: List<String>): String {
        for (k in keys) {
            val i = t.indexOf(k)
            if (i >= 0) return t.substring(i + k.length)
        }
        return ""
    }

    private fun timeString(): String {
        val f = SimpleDateFormat("h:mm a", Locale.ENGLISH)
        return "It's ${f.format(Date())}."
    }

    private fun dateString(): String {
        val f = SimpleDateFormat("EEEE, d MMMM", Locale.ENGLISH)
        return "Today is ${f.format(Date())}."
    }

    private fun extractMinutes(t: String): Int? {
        Regex("(\\d+)\\s*(minute|minutes|min|నిమిషం|నిమిషాల)").find(t)?.let { return it.groupValues[1].toIntOrNull() }
        return null
    }

    private fun extractClock(t: String): Pair<Int, Int>? {
        val m = Regex("(\\d{1,2})[:. ]?(\\d{2})?\\s*(am|pm)?").find(t) ?: return null
        var h = m.groupValues[1].toIntOrNull() ?: return null
        val min = m.groupValues[2].toIntOrNull() ?: 0
        val ap = m.groupValues[3]
        if (ap == "pm" && h < 12) h += 12
        if (ap == "am" && h == 12) h = 0
        if (h !in 0..23 || min !in 0..59) return null
        return h to min
    }

    private fun startTimer(seconds: Int, reply: (String) -> Unit) {
        val i = Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        tryStart(i, reply, "Timer set for ${seconds / 60} minutes.", "No clock app to set a timer.")
    }

    private fun setAlarm(h: Int, m: Int, reply: (String) -> Unit) {
        val i = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, h)
            putExtra(AlarmClock.EXTRA_MINUTES, m)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val f = SimpleDateFormat("h:mm a", Locale.ENGLISH).format(Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, h); set(Calendar.MINUTE, m)
        }.time)
        tryStart(i, reply, "Alarm set for $f.", "No clock app to set an alarm.")
    }

    private fun readNotifications(): String {
        if (ControlService.instance == null && NotifService.recent().isEmpty()) {
            return "Turn on notification reading in settings first."
        }
        val items = NotifService.recent()
        if (items.isEmpty()) return "No new notifications."
        val top = items.take(4).joinToString(". ") { "${it.app}: ${it.title} ${it.text}".trim() }
        return "You have ${items.size} recent. $top"
    }

    private fun callContact(name: String, reply: (String) -> Unit) {
        val number = lookupNumber(name)
        if (number == null) { reply("I couldn't find $name in your contacts."); return }
        needOpen(reply) {
            val i = Intent(Intent.ACTION_CALL, Uri.parse("tel:$number")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            tryStart(i, reply, "Calling $name.", "Give call permission in settings to place calls.")
        }
    }

    private fun lookupNumber(name: String): String? {
        return try {
            val uri = Uri.withAppendedPath(ContactsContract.CommonDataKinds.Phone.CONTENT_FILTER_URI, Uri.encode(name))
            ctx.contentResolver.query(uri, arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        } catch (e: Exception) { null }
    }

    private fun whatsappTarget(t: String): Pair<String, String>? {
        // "whatsapp <name> saying <message>" / "message <name> on whatsapp <message>"
        if (!t.contains("whatsapp") && !t.contains("వాట్సాప్")) return null
        val m = Regex("(?:whatsapp|వాట్సాప్)\\s+(.+?)\\s+(?:saying|message|msg|cheppu|చెప్పు|:)\\s+(.+)").find(t)
            ?: Regex("(?:message|msg|మెసేజ్)\\s+(.+?)\\s+(?:on whatsapp|whatsapp|వాట్సాప్)\\s+(.+)").find(t)
            ?: return null
        val name = m.groupValues[1].trim()
        val msg = m.groupValues[2].trim()
        return if (name.isNotBlank() && msg.isNotBlank()) name to msg else null
    }

    private fun sendWhatsApp(name: String, msg: String, reply: (String) -> Unit) {
        val number = lookupNumber(name)?.replace(Regex("[^0-9+]"), "")
        needOpen(reply) {
            val uri = if (number != null) {
                val n = if (number.startsWith("+")) number.drop(1) else "91$number"
                Uri.parse("https://api.whatsapp.com/send?phone=$n&text=${Uri.encode(msg)}")
            } else {
                Uri.parse("https://api.whatsapp.com/send?text=${Uri.encode(msg)}")
            }
            val i = Intent(Intent.ACTION_VIEW, uri).setPackage("com.whatsapp").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ControlService.instance?.armWhatsAppSend()
            try {
                ctx.startActivity(i)
                reply(if (ControlService.instance != null) "Sending on WhatsApp to $name." else "Opened WhatsApp. Tap send yourself, or turn on accessibility to auto-send.")
            } catch (e: Exception) {
                reply("Couldn't open WhatsApp.")
            }
        }
    }

    private fun openCamera(front: Boolean, reply: (String) -> Unit) {
        needOpen(reply) {
            val i = Intent(if (front) MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA else MediaStore.ACTION_IMAGE_CAPTURE)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val fallback = Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try { ctx.startActivity(i); reply("Opening camera.") }
            catch (e: Exception) {
                try { ctx.startActivity(fallback); reply("Opening camera.") }
                catch (e2: Exception) { reply("No camera app found.") }
            }
        }
    }

    private fun openApp(label: String, reply: (String) -> Unit) {
        val pm = ctx.packageManager
        val query = label.trim().lowercase()
        val apps = pm.getInstalledApplications(0)
        val match = apps.firstOrNull {
            val name = pm.getApplicationLabel(it).toString().lowercase()
            name == query || name.startsWith(query) || name.contains(query)
        }
        if (match != null) {
            val launch = pm.getLaunchIntentForPackage(match.packageName)
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                needOpen(reply) {
                    try { ctx.startActivity(launch); reply("Opening ${pm.getApplicationLabel(match)}.") }
                    catch (e: Exception) { reply("Couldn't open ${pm.getApplicationLabel(match)}.") }
                }
                return
            }
        }
        reply("I couldn't find an app called $label.")
    }

    private fun handleSearch(t: String, reply: (String) -> Unit) {
        val yt = t.contains("youtube")
        val q = afterAny(t, listOf("search for", "search", "youtube", "play", "సెర్చ్", "వెతుకు")).trim()
        if (q.isBlank()) { reply("What should I search for?"); return }
        needOpen(reply) {
            val uri = if (yt) Uri.parse("https://www.youtube.com/results?search_query=${Uri.encode(q)}")
            else Uri.parse("https://www.google.com/search?q=${Uri.encode(q)}")
            val i = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            tryStart(i, reply, if (yt) "Searching YouTube for $q." else "Searching for $q.", "No app to open that.")
        }
    }

    /** Make sure the phone is open before launching something, then run [block]. */
    private fun needOpen(reply: (String) -> Unit, block: () -> Unit) {
        Unlocker.unlockThen(ctx,
            say = { UiBus.post(it) },
            onFail = { reply(it) },
            action = block)
    }

    private fun tryStart(i: Intent, reply: (String) -> Unit, ok: String, fail: String) {
        try { ctx.startActivity(i); reply(ok) }
        catch (e: ActivityNotFoundException) { reply(fail) }
        catch (e: SecurityException) { reply(fail) }
        catch (e: Exception) { reply(fail) }
    }
}
