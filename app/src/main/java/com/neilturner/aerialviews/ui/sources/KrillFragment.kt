package com.neilturner.aerialviews.ui.sources

import android.os.Bundle
import android.os.SystemClock
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import com.neilturner.aerialviews.R
import com.neilturner.aerialviews.providers.custom.KrillDeviceAuth
import com.neilturner.aerialviews.providers.custom.KrillFrameCache
import com.neilturner.aerialviews.providers.custom.KrillLoginAttempts
import com.neilturner.aerialviews.providers.custom.KrillPrivateFeed
import com.neilturner.aerialviews.providers.custom.KrillSessionManager
import com.neilturner.aerialviews.providers.custom.KrillTokenStore
import com.neilturner.aerialviews.ui.controls.MenuStateFragment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

class KrillFragment : MenuStateFragment() {
    private var loginJob: Job? = null
    private var choiceDialog: AlertDialog? = null
    private val attempts = KrillLoginAttempts
    private var activeLoginAttempt: Long? = null

    companion object {
        private val accountScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        @Volatile private var pendingLogout: Job? = null
    }

    override fun onDestroy() {
        activeLoginAttempt?.let { if (attempts.isCurrent(it)) attempts.next() }
        choiceDialog?.dismiss()
        loginJob?.cancel()
        super.onDestroy()
    }

    override fun onCreatePreferences(
        savedInstanceState: Bundle?,
        rootKey: String?,
    ) {
        setPreferencesFromResource(R.xml.sources_krill, rootKey)
        findPreference<Preference>("krill_sign_in")?.setOnPreferenceClickListener {
            startLogin()
            true
        }
        findPreference<Preference>("krill_sign_out")?.setOnPreferenceClickListener {
            val attempt = attempts.next()
            activeLoginAttempt = null
            choiceDialog?.dismiss()
            loginJob?.cancel()
            val context = requireContext().applicationContext
            val previousLogout = pendingLogout
            val logout =
                accountScope.launch(start = CoroutineStart.LAZY) {
                    try {
                        previousLogout?.join()
                        val signedOut = KrillFrameCache.signOut(context)
                        if (signedOut &&
                            attempts.isCurrent(attempt)
                        ) {
                            withContext(Dispatchers.Main) { Toast.makeText(context, R.string.krill_signed_out, Toast.LENGTH_SHORT).show() }
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        withContext(Dispatchers.Main) { Toast.makeText(context, R.string.krill_logout_failed, Toast.LENGTH_LONG).show() }
                    }
                }
            pendingLogout = logout
            logout.start()
            true
        }
    }

    private fun startLogin() {
        val attempt = attempts.next()
        activeLoginAttempt = attempt
        choiceDialog?.dismiss()
        loginJob?.cancel()
        loginJob = lifecycleScope.launch { signIn(attempt) }
    }

    private suspend fun signIn(attempt: Long) {
        val context = requireContext().applicationContext
        val origin =
            findPreference<EditTextPreference>("krill_library_origin")
                ?.text
                .orEmpty()
                .trim()
                .trimEnd('/')
        val client = OkHttpClient()
        var dialog: AlertDialog? = null
        try {
            val config = withContext(Dispatchers.IO) { KrillPrivateFeed.config(client, origin) }
            val auth = KrillDeviceAuth(client, config.issuer, config.clientId)
            val device = withContext(Dispatchers.IO) { auth.start() }
            val deadline = SystemClock.elapsedRealtime() + device.expiresIn * 1000L
            dialog =
                AlertDialog
                    .Builder(requireContext())
                    .setTitle(R.string.krill_login_title)
                    .setMessage(getString(R.string.krill_login_instructions, device.verificationUri, device.userCode))
                    .setNegativeButton(R.string.button_cancel) { _, _ -> loginJob?.cancel() }
                    .setOnCancelListener { loginJob?.cancel() }
                    .show()
            var interval = device.interval.toLong()
            var tokens: KrillDeviceAuth.Tokens? = null
            while (SystemClock.elapsedRealtime() < deadline && tokens == null) {
                delay(interval * 1000L)
                if (SystemClock.elapsedRealtime() >= deadline) break
                when (val result = withContext(Dispatchers.IO) { auth.poll(device.deviceCode) }) {
                    is KrillDeviceAuth.PollResult.Authorized -> tokens = result.tokens
                    KrillDeviceAuth.PollResult.Pending -> Unit
                    KrillDeviceAuth.PollResult.SlowDown -> interval += 5
                    is KrillDeviceAuth.PollResult.Rejected -> error("Device authorization denied")
                }
            }
            val granted = tokens ?: error("Device authorization expired")
            val refresh = granted.refreshToken ?: error("Missing refresh token")
            val spaces = withContext(Dispatchers.IO) { KrillPrivateFeed(client, origin, granted.accessToken).spaces() }
            if (!attempts.isCurrent(attempt)) return
            dialog.dismiss()
            dialog = null
            if (spaces.isEmpty()) {
                Toast.makeText(context, R.string.krill_no_spaces, Toast.LENGTH_LONG).show()
                return
            }
            val expiry = System.currentTimeMillis() + granted.expiresIn * 1000L
            choiceDialog =
                AlertDialog
                    .Builder(requireContext())
                    .setTitle(R.string.krill_choose_space)
                    .setItems(spaces.map { it.name }.toTypedArray()) { _, index ->
                        lifecycleScope.launch {
                            try {
                                pendingLogout?.join()
                                if (!attempts.isCurrent(attempt)) return@launch
                                val saved =
                                    withContext(Dispatchers.IO) {
                                        if (!KrillFrameCache.signOut(context) { attempts.isCurrent(attempt) }) return@withContext false
                                        val didSave =
                                            KrillSessionManager(context).saveIf(
                                                KrillTokenStore.Session(
                                                    config.issuer,
                                                    config.clientId,
                                                    origin,
                                                    granted.accessToken,
                                                    refresh,
                                                    expiry,
                                                    spaces[index].id,
                                                ),
                                            ) { attempts.isCurrent(attempt) }
                                        if (!didSave) return@withContext false
                                        true
                                    }
                                if (saved &&
                                    attempts.isCurrent(attempt)
                                ) {
                                    Toast.makeText(context, R.string.krill_login_success, Toast.LENGTH_SHORT).show()
                                }
                            } catch (_: Exception) {
                                Toast.makeText(context, R.string.krill_login_failed, Toast.LENGTH_LONG).show()
                            }
                        }
                    }.setNegativeButton(R.string.button_cancel, null)
                    .show()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            Toast.makeText(context, R.string.krill_login_failed, Toast.LENGTH_LONG).show()
        } finally {
            dialog?.dismiss()
        }
    }
}
