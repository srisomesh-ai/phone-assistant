package com.srisomesh.assistant

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper

/**
 * When a command needs the phone open, wake the screen and bring up the phone's own
 * unlock screen (face / fingerprint / PIN). As soon as you unlock, the command continues.
 */
object Unlocker {
    private val main = Handler(Looper.getMainLooper())

    fun isLocked(ctx: Context): Boolean =
        (ctx.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked

    fun isCharging(ctx: Context): Boolean {
        val i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return false
        return i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
    }

    fun batteryPercent(ctx: Context): Int {
        val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        return bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }

    /** Runs [action] once the phone is unlocked (waits up to 20 s for you to unlock). */
    fun unlockThen(ctx: Context, say: (String) -> Unit, onFail: (String) -> Unit, action: () -> Unit) {
        if (!isLocked(ctx)) { action(); return }

        say("Please unlock")
        ListenService.instance?.showResponseScreen(unlock = true)

        val deadline = System.currentTimeMillis() + 20_000
        val poll = object : Runnable {
            override fun run() {
                when {
                    !isLocked(ctx) -> main.postDelayed(action, 400)
                    System.currentTimeMillis() > deadline -> onFail("Phone is still locked")
                    else -> main.postDelayed(this, 300)
                }
            }
        }
        main.postDelayed(poll, 500)
    }
}
