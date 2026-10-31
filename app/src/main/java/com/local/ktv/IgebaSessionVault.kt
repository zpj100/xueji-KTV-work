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

/** Private, backup-excluded session storage; no passwords are accepted here.
 *  Android 5.0+ 用 Keystore+AES-GCM 加密；更低版本退化为私有目录存储（无 Keystore/GCM 能力）。 */
internal class IgebaSessionVault(context: Context) {
    private val context = context.applicationContext
    private val file = File(context.noBackupFilesDir, "igeba-session.bin")
    private val alias = "maidong.igeba.session"
    private val encrypted = Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP

    private fun encode(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)
    private fun decode(value: String) = Base64.decode(value, Base64.NO_WRAP)
    private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun ensureKey() {
        if (keyStore().containsAlias(alias)) return
        val start = Calendar.getInstance()
        val end = Calendar.getInstance().apply { add(Calendar.YEAR, 30) }
        val spec = KeyPairGeneratorSpec.Builder(context).setAlias(alias)
            .setSubject(X500Principal("CN=Maidong Igeba Session"))
            .setSerialNumber(BigInteger.ONE).setStartDate(start.time).setEndDate(end.time)
            .setKeySize(2048).build()
        KeyPairGenerator.getInstance("RSA", "AndroidKeyStore").apply { initialize(spec) }.generateKeyPair()
    }

    @Synchronized fun save(value: JSONObject) {
        if (!encrypted) {
            AtomicFileCompat.writeText(file, value.toString())
            return
        }
        ensureKey()
        val secret = KeyGenerator.getInstance("AES").apply { init(256, SecureRandom()) }.generateKey()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, secret) }
        val sealed = cipher.doFinal(value.toString().toByteArray(Charsets.UTF_8))
        val wrapper = Cipher.getInstance("RSA/ECB/PKCS1Padding").apply {
            init(Cipher.ENCRYPT_MODE, keyStore().getCertificate(alias).publicKey)
        }
        val record = JSONObject().put("version", 1).put("key", encode(wrapper.doFinal(secret.encoded)))
            .put("iv", encode(cipher.iv)).put("data", encode(sealed)).toString()
        AtomicFileCompat.writeText(file, record)
    }

    @Synchronized fun load(): JSONObject? {
        val raw = AtomicFileCompat.readText(file) ?: return null
        if (!encrypted) return runCatching { JSONObject(raw) }.getOrNull()
        return runCatching {
            val record = JSONObject(raw)
            require(record.getInt("version") == 1)
            val wrapper = Cipher.getInstance("RSA/ECB/PKCS1Padding").apply {
                init(Cipher.DECRYPT_MODE, keyStore().getKey(alias, null))
            }
            val key = SecretKeySpec(wrapper.doFinal(decode(record.getString("key"))), "AES")
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, decode(record.getString("iv"))))
            }
            JSONObject(String(cipher.doFinal(decode(record.getString("data"))), Charsets.UTF_8))
        }.getOrNull()
    }

    @Synchronized fun clear() { runCatching { file.delete() } }
}
