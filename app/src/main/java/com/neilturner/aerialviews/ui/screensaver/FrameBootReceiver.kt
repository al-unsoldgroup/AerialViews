package com.neilturner.aerialviews.ui.screensaver

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import com.neilturner.aerialviews.models.prefs.GeneralPrefs
import timber.log.Timber

class FrameBootReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        enableWirelessDebugging(context)
        if (!GeneralPrefs.startFrameOnBoot) return
        try {
            context.startActivity(Intent(context, TestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (error: RuntimeException) {
            Timber.w(error, "Unable to launch frame after boot")
        }
    }

    /**
     * Turns wireless debugging back on after a reboot so the frame stays reachable over adb.
     * Needs WRITE_SECURE_SETTINGS, granted once with `adb shell pm grant <package> android.permission.WRITE_SECURE_SETTINGS`.
     */
    private fun enableWirelessDebugging(context: Context) {
        if (context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) != PackageManager.PERMISSION_GRANTED) return
        try {
            Settings.Global.putInt(context.contentResolver, "adb_wifi_enabled", 1)
        } catch (error: RuntimeException) {
            Timber.w(error, "Unable to enable wireless debugging after boot")
        }
    }
}
