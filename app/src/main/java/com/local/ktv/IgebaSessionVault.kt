package com.local.ktv

import android.content.Context
import android.os.Build
import android.security.KeyPairGeneratorSpec
import android.util.Base64
import org.json.JSONObject
import java.io.File
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.util.Calendar
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.security.auth.x500.X500Principal

/**
 * 私有会话存储（不保存密码）。
 * Android 5.0+ 用 Keystore + AES-GCM；4.x 退化为私有目录 JSON。
 * 注意：GCM/Keystore 代码全部隔离在 [Crypto] 中，确保 4.x 不会解析 API 21 的类。
 */
internal class IgebaSessionVault(context: Context) {
    private val appContext = context.applicationContext
    private val file: File = File(
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) appContext.noBackupFilesDir else appContext.filesDir,
        "igeba-session.bin",
    )
    private val encrypted = Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP

    @Synchronized fun save(value: JSONObject) {
        if (!encrypted) {
            AtomicFileCompat.writeText(file, value.toString())
            return
        }
        Crypto.save(appContext, file, value)
    }

    @Synchronized fun load(): JSONObject? {
        val raw = AtomicFileCompat.readText(file) ?: return null
        if (!encrypted) return runCatching { JSONObject(raw) }.getOrNull()
        return Crypto.load(file, raw)
    }

    @Synchronized fun clear() {
        runCatching { file.delete() }
    }

    /** 仅在 Android 5.0+ 加载。 */
    private object Crypto {
        private const val ALIAS = "maidong.igeba.session"

        private fun encode(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)
        private fun decode(value: String) = Base64.decode(value, Base64.NO_WRAP)
        private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

        private fun ensureKey(context: Context) {
            if (keyStore().containsAlias(ALIAS)) return
            val start = Calendar.getInstance()
            val end = Calendar.getInstance().apply { add(Calendar.YEAR, 30) }
            val spec = KeyPairGeneratorSpec.Builder(context).setAlias(ALIAS)
                .setSubject(X500Principal("CN=Maidong Igeba Session"))
                .setSerialNumber(BigInteger.ONE).setStartDate(start.time).setEndDate(end.time)
                .setKeySize(2048).build()
            KeyPairGenerator.getInstance("RSA", "AndroidKeyStore").apply { initialize(spec) }.generateKeyPair()
        }

        fun save(context: Context, file: File, value: JSONObject) {
            ensureKey(context)
            val secret = KeyGenerator.getInstance("AES").apply { init(256, SecureRandom()) }.generateKey()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, secret) }
            val sealed = cipher.doFinal(value.toString().toByteArray(Charsets.UTF_8))
            val wrapper = Cipher.getInstance("RSA/ECB/PKCS1Padding").apply {
                init(Cipher.ENCRYPT_MODE, keyStore().getCertificate(ALIAS).publicKey)
            }
            val record = JSONObject().put("version", 1).put("key", encode(wrapper.doFinal(secret.encoded)))
                .put("iv", encode(cipher.iv)).put("data", encode(sealed)).toString()
            AtomicFileCompat.writeText(file, record)
        }

        fun load(file: File, raw: String): JSONObject? = runCatching {
            val record = JSONObject(raw)
            require(record.getInt("version") == 1)
            val wrapper = Cipher.getInstance("RSA/ECB/PKCS1Padding").apply {
                init(Cipher.DECRYPT_MODE, keyStore().getKey(ALIAS, null))
            }
            val key = SecretKeySpec(wrapper.doFinal(decode(record.getString("key"))), "AES")
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, decode(record.getString("iv"))))
            }
            JSONObject(String(cipher.doFinal(decode(record.getString("data"))), Charsets.UTF_8))
        }.getOrNull()
    }
}
