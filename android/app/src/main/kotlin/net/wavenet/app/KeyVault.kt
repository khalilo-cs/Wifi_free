package net.wavenet.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import net.wavenet.proto.Identity

/**
 * The node's Ed25519 seed is stored encrypted (AES-256-GCM) with a key that lives inside the Android Keystore and
 * never leaves it. (Android only supports Ed25519 keys in the Keystore from API 33; this works from API 26.)
 * File: <filesDir>/identity.enc = 12-byte IV || ciphertext+tag. allowBackup=false keeps it off cloud backups.
 */
object KeyVault {
    private const val ALIAS = "wavenet-identity-wrap-v1"
    private const val FILE = "identity.enc"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        g.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256).build())
        return g.generateKey()
    }

    fun loadOrCreate(ctx: Context): Identity {
        val f = File(ctx.filesDir, FILE)
        if (f.exists()) {
            try {
                val b = f.readBytes()
                val c = Cipher.getInstance("AES/GCM/NoPadding")
                c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, b, 0, 12))
                return Identity.fromSeed(c.doFinal(b, 12, b.size - 12))
            } catch (e: Exception) {
                // Keystore key lost (e.g. device reset of credentials): a new identity is the only safe option.
                f.delete()
            }
        }
        val id = Identity.generate()
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        val tmp = File(ctx.filesDir, "$FILE.tmp")
        tmp.writeBytes(c.iv + c.doFinal(id.seed()))
        tmp.renameTo(f)
        return id
    }
}
