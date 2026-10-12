package app.trazo.data.remote

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import io.github.jan.supabase.auth.SessionManager
import io.github.jan.supabase.auth.user.UserSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stores the Supabase session (access + refresh token) encrypted with an AES-256-GCM key
 * that lives in the Android Keystore and never leaves it.
 */
class SecureSessionManager(context: Context, private val json: Json) : SessionManager {
    private val file = File(context.noBackupFilesDir, "session.bin")

    override suspend fun saveSession(session: UserSession) = withContext(Dispatchers.IO) {
        val plain = json.encodeToString(UserSession.serializer(), session).toByteArray()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val enc = cipher.doFinal(plain)
        val payload = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(enc, Base64.NO_WRAP)
        file.writeText(payload)
    }

    override suspend fun loadSession(): UserSession? = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext null
        runCatching {
            val (iv, enc) = file.readText().split(":").let { Base64.decode(it[0], Base64.NO_WRAP) to Base64.decode(it[1], Base64.NO_WRAP) }
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            json.decodeFromString(UserSession.serializer(), String(cipher.doFinal(enc)))
        }.getOrElse {
            file.delete()
            null
        }
    }

    override suspend fun deleteSession() {
        withContext(Dispatchers.IO) { file.delete() }
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    private companion object {
        const val ALIAS = "trazo_session_key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
