package com.neilturner.aerialviews.ui.settings

import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.preference.Preference
import com.neilturner.aerialviews.R
import com.neilturner.aerialviews.models.prefs.GeneralPrefs
import com.neilturner.aerialviews.ui.controls.MenuStateFragment
import com.neilturner.aerialviews.ui.core.DashboardConfig
import com.neilturner.aerialviews.utils.FirebaseHelper
import com.neilturner.aerialviews.utils.toStringOrEmpty

class AppearanceStartupFragment :
    MenuStateFragment(),
    SharedPreferences.OnSharedPreferenceChangeListener {
    override fun onCreatePreferences(
        savedInstanceState: Bundle?,
        rootKey: String?,
    ) {
        GeneralPrefs.migrateFrameSchedule()
        setPreferencesFromResource(R.xml.settings_appearance_startup, rootKey)
        findPreference<Preference>("frame_art_amount")?.setOnPreferenceChangeListener { _, value ->
            val amount = (value as? String)?.trim()?.toIntOrNull()
            val valid = amount != null && amount >= 0 && (GeneralPrefs.frameArtCadence != "PERCENT" || amount <= 100)
            if (!valid) Toast.makeText(requireContext(), R.string.frame_art_invalid, Toast.LENGTH_LONG).show()
            valid
        }
        listOf("frame_dashboard_url", "frame_schedule_start", "frame_schedule_end").forEach { key ->
            findPreference<Preference>(key)?.setOnPreferenceChangeListener { _, value ->
                val text = (value as? String)?.trim().orEmpty()
                val valid =
                    if (key == "frame_dashboard_url") {
                        text.isEmpty() || DashboardConfig.validUrl(text)
                    } else {
                        DashboardConfig.parseTime(text) != null
                    }
                if (!valid) Toast.makeText(requireContext(), R.string.frame_dashboard_invalid, Toast.LENGTH_LONG).show()
                valid
            }
        }
        findPreference<Preference>("startup_frame_on_boot")?.setOnPreferenceChangeListener { _, newValue ->
            if (newValue == true && !Settings.canDrawOverlays(requireContext())) {
                val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${requireContext().packageName}"))
                if (intent.resolveActivity(requireContext().packageManager) != null) {
                    startActivity(intent)
                } else {
                    Toast.makeText(requireContext(), R.string.frame_boot_permission_needed, Toast.LENGTH_LONG).show()
                }
            }
            true
        }
    }

    override fun onResume() {
        super.onResume()
        FirebaseHelper.analyticsScreenView("Startup", this)
        showStartScreensaverOnLaunchOption()
    }

    override fun onDestroy() {
        preferenceManager.sharedPreferences?.unregisterOnSharedPreferenceChangeListener(this)
        super.onDestroy()
    }

    override fun onSharedPreferenceChanged(
        sharedPreferences: SharedPreferences?,
        key: String?,
    ) {
        showStartScreensaverOnLaunchOption()
    }

    private fun showStartScreensaverOnLaunchOption() {
        val message = preferenceScreen.findPreference<Preference>("exit_to_settings")
        var showMessage = true

        GeneralPrefs.preferences.all.forEach {
            if (it.key.startsWith("button_") &&
                (it.key.endsWith("_press") || it.key.endsWith("_hold")) &&
                it.value.toStringOrEmpty().contains("EXIT_TO_SETTINGS")
            ) {
                showMessage = false
                return@forEach
            }
        }

        message?.isVisible = showMessage
    }
}
