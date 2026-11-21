package com.catsoftfon.sip.sip

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.catsoftfon.sip.data.JsonStore

/**
 * Mirrors the Windows installer's optional "run at Windows sign-in" shortcut: if the user
 * turned on AppSettings.startOnBoot for ANY of the (up to 3) lines, launch MainActivity right
 * after the device finishes booting so the phone starts registering with the PBX immediately
 * (CatSoftFonApplication.onCreate() itself re-registers every already-configured line, so
 * just launching the app is enough - no per-line logic needed here beyond the check).
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val shouldStart = (0 until SipManager.LINE_COUNT).any { JsonStore(context, it).loadSettings().startOnBoot }
        if (!shouldStart) return

        val launchIntent = Intent(context, com.catsoftfon.sip.ui.MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(launchIntent)
    }
}
