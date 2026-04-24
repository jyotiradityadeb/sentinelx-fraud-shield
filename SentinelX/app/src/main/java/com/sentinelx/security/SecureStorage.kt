package com.sentinelx.security

import android.content.Context
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import java.util.concurrent.atomic.AtomicBoolean

class SecureStorage(private val context: Context) {
    companion object {
        // AUDIO_NEVER_STORED: SentinelX never stores raw call audio or PCM buffers. Only derived numeric features and event metadata may be encrypted.
        private const val KEYSET_NAME = "sentinelx_secure_keyset"
        private const val PREF_FILE_NAME = "sentinelx_secure_prefs"
        private const val KEY_ALIAS = "sentinelx_aead_key"
        private val CONFIG_REGISTERED = AtomicBoolean(false)
        private val ASSOCIATED_DATA = "sentinelx-event-batch".toByteArray()
    }

    private val aead: Aead by lazy { buildAead() }

    fun encryptEventBatch(json: String): ByteArray {
        return aead.encrypt(json.toByteArray(Charsets.UTF_8), ASSOCIATED_DATA)
    }

    fun decryptEventBatch(encrypted: ByteArray): String {
        val clear = aead.decrypt(encrypted, ASSOCIATED_DATA)
        return clear.toString(Charsets.UTF_8)
    }

    fun secureDelete(data: ByteArray) {
        data.fill(0)
    }

    private fun buildAead(): Aead {
        ensureAeadConfig()
        val manager = AndroidKeysetManager.Builder()
            .withSharedPref(context, KEYSET_NAME, PREF_FILE_NAME)
            .withKeyTemplate(KeyTemplates.get("AES256_GCM"))
            .withMasterKeyUri("android-keystore://$KEY_ALIAS")
            .build()
        return manager.keysetHandle.getPrimitive(Aead::class.java)
    }

    private fun ensureAeadConfig() {
        if (CONFIG_REGISTERED.compareAndSet(false, true)) {
            AeadConfig.register()
        }
    }
}
