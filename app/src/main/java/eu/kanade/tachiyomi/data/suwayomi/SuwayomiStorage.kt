// KMK -->
package eu.kanade.tachiyomi.data.suwayomi

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

@Serializable
internal data class StoredSession(
    val id: String = UUID.randomUUID().toString(),
    val baseUrl: String,
    val mode: SuwayomiAuthMode,
    val username: String = "",
    val password: String = "",
    val accessToken: String = "",
    val refreshToken: String = "",
    val cookies: List<String> = emptyList(),
    val userId: Int? = null,
) {
    override fun toString(): String = "StoredSession(id=$id, mode=$mode, credentials=redacted)"
}

/** Keystore-encrypted, atomic and excluded from Android backups. */
internal class SuwayomiStorage(context: Context) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "suwayomi-session"))
    private val key: SecretKey by lazy {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey) ?: KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            "AndroidKeyStore",
        ).apply {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
        }.generateKey()
    }

    @Synchronized
    fun read(): StoredSession? {
        if (!file.baseFile.exists()) return null
        return try {
            val bytes = file.readFully()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            Json.decodeFromString<StoredSession>(cipher.doFinal(bytes.copyOfRange(12, bytes.size)).decodeToString())
        } catch (_: Exception) {
            file.delete()
            null
        }
    }

    @Synchronized
    fun write(session: StoredSession) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val bytes = cipher.iv + cipher.doFinal(Json.encodeToString(session).encodeToByteArray())
        val stream = file.startWrite()
        try {
            stream.write(bytes)
            file.finishWrite(stream)
        } catch (e: Exception) {
            file.failWrite(stream)
            throw e
        }
    }

    @Synchronized
    fun clear() = file.delete()

    private companion object {
        const val KEY_ALIAS = "komikku.suwayomi.session.v1"
    }
}
// KMK <--
