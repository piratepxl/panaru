package app.panaru.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Testo cifrato + vettore di inizializzazione, salvati nel database. */
class Sealed(val cipherText: ByteArray, val iv: ByteArray)

/** Astrazione per cifrare segreti (sostituibile nei test). */
interface SecretCipher {
    fun seal(plain: String): Sealed
    fun open(sealed: Sealed): String
}

/**
 * Cifra le API key con AES-256-GCM usando una chiave generata nell'**Android Keystore**:
 * la chiave non lascia mai l'hardware sicuro / il TEE e non è esportabile.
 * Nel database finiscono solo ciphertext e IV, mai la chiave in chiaro.
 */
class KeystoreSecretBox(private val alias: String = "panaru.apikeys.v1") : SecretCipher {

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return gen.generateKey()
    }

    override fun seal(plain: String): Sealed {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        return Sealed(cipher.doFinal(plain.toByteArray(Charsets.UTF_8)), cipher.iv)
    }

    override fun open(sealed: Sealed): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, sealed.iv))
        return String(cipher.doFinal(sealed.cipherText), Charsets.UTF_8)
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
