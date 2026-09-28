package com.openwrtmanager.mobile.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.openwrtmanager.mobile.model.RouterProfile
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SecureStore(context: Context) {
    private val prefs = context.getSharedPreferences("secure_router", Context.MODE_PRIVATE)
    private val alias = "openwrt_manager_profile_key"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    fun saveProfile(profile: RouterProfile) {
        val json = JSONObject()
            .put("host", profile.host)
            .put("port", profile.port)
            .put("username", profile.username)
            .put("password", profile.password)
            .toString().toByteArray()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val payload = cipher.iv + cipher.doFinal(json)
        prefs.edit().putString("profile", Base64.encodeToString(payload, Base64.NO_WRAP)).apply()
    }

    fun loadProfile(): RouterProfile? {
        val encoded = prefs.getString("profile", null) ?: return null
        return runCatching {
            val payload = Base64.decode(encoded, Base64.NO_WRAP)
            val iv = payload.copyOfRange(0, 12)
            val encrypted = payload.copyOfRange(12, payload.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            val obj = JSONObject(String(cipher.doFinal(encrypted)))
            RouterProfile(
                host = obj.getString("host"),
                port = obj.optInt("port", 22),
                username = obj.optString("username", "root"),
                password = obj.getString("password")
            )
        }.getOrNull()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    fun getHostFingerprint(host: String, port: Int): String? = prefs.getString("fp:$host:$port", null)
    fun saveHostFingerprint(host: String, port: Int, fingerprint: String) {
        prefs.edit().putString("fp:$host:$port", fingerprint).apply()
    }
}
