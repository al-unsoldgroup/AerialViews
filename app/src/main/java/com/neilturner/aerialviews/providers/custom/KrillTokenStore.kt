package com.neilturner.aerialviews.providers.custom

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import com.neilturner.aerialviews.data.network.JsonHelper
import kotlinx.serialization.Serializable
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Tokens are encrypted with a non-exportable device key and excluded from Android backup. */
internal class KrillTokenStore(
    context: Context,
) {
    @Serializable
    data class Session(
        val issuer: String,
        val clientId: String,
        val libraryOrigin: String,
        val accessToken: String,
        val refreshToken: String,
        val expiresAtMillis: Long,
        val spaceId: String? = null,
        // Generated for each sign-in and stored with the encrypted credentials.
        val cacheNamespace: String = "",
    ) {
        override fun toString(): String =
            "Session(issuer=$issuer, clientId=$clientId, libraryOrigin=$libraryOrigin, expiresAtMillis=$expiresAtMillis)"
    }

    private val file = AtomicFile(File(context.noBackupFilesDir, "krill-frame-session"))
    private val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private val alias = "krill-frame-session-v1"

    private fun key(): SecretKey {
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator
            .getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            .apply {
                init(
                    KeyGenParameterSpec
                        .Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .build(),
                )
            }.generateKey()
    }

    @Synchronized
    fun save(session: Session) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(JsonHelper.json.encodeToString(Session.serializer(), session).toByteArray(Charsets.UTF_8))
        val stream = file.startWrite()
        try {
            stream.write(cipher.iv)
            stream.write(encrypted)
            file.finishWrite(stream)
        } catch (error: Exception) {
            file.failWrite(stream)
            throw error
        }
    }

    @Synchronized
    fun load(): Session? {
        if (!file.baseFile.exists()) return null
        val data = file.openRead().use { it.readBytes() }
        require(data.size in 29..65536) { "Invalid encrypted frame session" }
        // Android Keystore generates a 96-bit GCM IV.
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val existingKey = keyStore.getKey(alias, null) as? SecretKey ?: return null
        cipher.init(Cipher.DECRYPT_MODE, existingKey, GCMParameterSpec(128, data.copyOfRange(0, 12)))
        val plain = cipher.doFinal(data, 12, data.size - 12).toString(Charsets.UTF_8)
        return JsonHelper.json.decodeFromString<Session>(plain)
    }

    @Synchronized
    fun clear() {
        file.delete()
        keyStore.deleteEntry(alias)
    }
}
