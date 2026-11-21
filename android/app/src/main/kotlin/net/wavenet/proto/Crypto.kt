package net.wavenet.proto

import java.security.MessageDigest
import java.security.SecureRandom
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer

/** Standard primitives only: SHA-256 (JCA), Ed25519 (Bouncy Castle, RFC 8032), SecureRandom. */
object Crypto {
    val rng = SecureRandom()
    fun sha256(b: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(b)
    fun random(n: Int): ByteArray = ByteArray(n).also { rng.nextBytes(it) }
    fun verify(pub: ByteArray, msg: ByteArray, sig: ByteArray): Boolean {
        if (pub.size != 32 || sig.size != 64) return false
        return try {
            val v = Ed25519Signer(); v.init(false, Ed25519PublicKeyParameters(pub, 0)); v.update(msg, 0, msg.size); v.verifySignature(sig)
        } catch (e: Exception) { false }
    }
}

/** Node identity. NodeID = SHA-256(raw Ed25519 public key) — same as the C++ node. */
class Identity private constructor(private val priv: Ed25519PrivateKeyParameters) {
    val publicKey: ByteArray = priv.generatePublicKey().encoded
    val nodeId: ByteArray = Crypto.sha256(publicKey)
    val nodeIdHex: String get() = Hex.encode(nodeId)
    /** 32-byte private seed — callers must store it securely (Android: encrypted with an Android Keystore key). */
    fun seed(): ByteArray = priv.encoded

    fun sign(msg: ByteArray): ByteArray {
        val s = Ed25519Signer(); s.init(true, priv); s.update(msg, 0, msg.size); return s.generateSignature()
    }

    companion object {
        fun generate(): Identity = Identity(Ed25519PrivateKeyParameters(Crypto.rng))
        fun fromSeed(seed: ByteArray): Identity {
            require(seed.size == 32) { "Ed25519 seed must be 32 bytes" }
            return Identity(Ed25519PrivateKeyParameters(seed, 0))
        }
    }
}
