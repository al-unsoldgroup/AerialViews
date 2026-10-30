package com.neilturner.aerialviews.ui.screensaver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.neilturner.aerialviews.models.prefs.GeneralPrefs
import timber.log.Timber

class FrameBootReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED || !GeneralPrefs.startFrameOnBoot) return
        try {
            context.startActivity(Intent(context, TestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (error: RuntimeException) {
            Timber.w(error, "Unable to launch frame after boot")
        }
    }
}
