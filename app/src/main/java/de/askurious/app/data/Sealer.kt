package de.askurious.app.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Seals a text for storage and opens it again; [open] gives null for anything it cannot open. */
interface Sealer {
    fun seal(plain: String): String
    fun open(sealed: String): String?
}

/**
 * The secrets of the studies, sealed with AES-GCM under a key that lives in the Android Keystore and never leaves it
 * (architecture concept 2026-10-12, K4). The stored text alone (a copy of the app's files, a rooted phone's backup)
 * gives no secret. EncryptedSharedPreferences is deprecated, so the Keystore directly. No user authentication is
 * bound to the key: a prompt must be able to open the study in the background, also before the phone was unlocked.
 */
object KeystoreSealer : Sealer {
    private const val STORE = "AndroidKeyStore"
    private const val ALIAS = "askurious_secrets"
    private const val PREFIX = "k1:"
    private const val IV_BYTES = 12

    @Synchronized
    private fun key(): SecretKey {
        val store = KeyStore.getInstance(STORE).apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, STORE)
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    override fun seal(plain: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        return PREFIX + Base64.getEncoder().encodeToString(cipher.iv + cipher.doFinal(plain.toByteArray()))
    }

    override fun open(sealed: String): String? = runCatching {
        if (!sealed.startsWith(PREFIX)) return null
        val bytes = Base64.getDecoder().decode(sealed.removePrefix(PREFIX))
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, IV_BYTES))
        String(cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES))
    }.getOrNull()
}
