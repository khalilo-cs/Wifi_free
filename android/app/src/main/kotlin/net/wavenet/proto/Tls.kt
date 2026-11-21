package net.wavenet.proto

import java.io.InputStream
import java.io.OutputStream
import java.math.BigInteger
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.Date
import org.bouncycastle.asn1.edec.EdECObjectIdentifiers
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.AlgorithmIdentifier
import org.bouncycastle.cert.X509v3CertificateBuilder
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.bouncycastle.crypto.util.SubjectPublicKeyInfoFactory
import org.bouncycastle.operator.ContentSigner
import org.bouncycastle.tls.Certificate
import org.bouncycastle.tls.CertificateEntry
import org.bouncycastle.tls.CertificateRequest
import org.bouncycastle.tls.DefaultTlsClient
import org.bouncycastle.tls.DefaultTlsServer
import org.bouncycastle.tls.ProtocolVersion
import org.bouncycastle.tls.SignatureAndHashAlgorithm
import org.bouncycastle.tls.TlsAuthentication
import org.bouncycastle.tls.TlsClientProtocol
import org.bouncycastle.tls.TlsCredentials
import org.bouncycastle.tls.TlsServerCertificate
import org.bouncycastle.tls.TlsServerProtocol
import org.bouncycastle.tls.TlsUtils
import org.bouncycastle.tls.crypto.TlsCryptoParameters
import org.bouncycastle.tls.crypto.impl.bc.BcDefaultTlsCredentialedSigner
import org.bouncycastle.tls.crypto.impl.bc.BcTlsCrypto

class TransportError(msg: String) : Exception(msg)

/** A reliable link carrying whole WAVE-NP frames (mirror of the C++ `Link`). */
interface Link {
    fun sendFrame(frame: ByteArray)
    fun recvFrame(timeoutMs: Int): ByteArray
    fun close()
    val describe: String
    val channelBinding: ByteArray
    val encrypted: Boolean
}

/**
 * TLS 1.3 only (Bouncy Castle). Certificates are ephemeral and not verified: identity is proven by the WAVE-NP handshake,
 * which signs the TLS exporter value "EXPORTER-WAVE-NP-v1" (channel binding) — identical to the C++ node (D12).
 */
object Tls {
    const val EXPORTER_LABEL = "EXPORTER-WAVE-NP-v1"
    private val bcCrypto by lazy { BcTlsCrypto(Crypto.rng) }

    fun connect(host: String, port: Int, timeoutMs: Int = 10_000, medium: String = "tcp"): Link {
        val s = Socket()
        try {
            s.connect(InetSocketAddress(host, port), timeoutMs)
            s.tcpNoDelay = true
            s.soTimeout = timeoutMs
        } catch (e: Exception) { s.close(); throw TransportError("connect failed ($host:$port): ${e.message}") }
        return clientWrap(ByteChannel.of(s, "tls+$medium:$host:$port"), timeoutMs)
    }

    /** TLS 1.3 client over any byte channel (TCP, Bluetooth RFCOMM, pipe). */
    fun clientWrap(ch: ByteChannel, timeoutMs: Int = 10_000): Link {
        var binding: ByteArray? = null
        val proto = TlsClientProtocol(ch.input, ch.output)
        val client = object : DefaultTlsClient(bcCrypto) {
            override fun getSupportedVersions(): Array<ProtocolVersion> = ProtocolVersion.TLSv13.only()
            override fun getAuthentication(): TlsAuthentication = object : TlsAuthentication {
                override fun notifyServerCertificate(c: TlsServerCertificate) { /* authenticated by channel binding */ }
                override fun getClientCredentials(r: CertificateRequest): TlsCredentials? = null
            }
            override fun notifyHandshakeComplete() {
                super.notifyHandshakeComplete()
                binding = context.exportKeyingMaterial(EXPORTER_LABEL, null, 32)
            }
        }
        try { ch.withTimeout(timeoutMs) { proto.connect(client) } } catch (e: Exception) { ch.close(); throw TransportError("TLS handshake failed: ${e.message}") }
        return StreamLink(ch, proto.inputStream, proto.outputStream, ch.name, binding ?: throw TransportError("no TLS exporter"))
    }

    /** Ephemeral self-signed Ed25519 certificate (needed only so TLS 1.3 can run; never trusted). */
    private class ServerCred {
        val key = Ed25519PrivateKeyParameters(Crypto.rng)
        val der: ByteArray
        init {
            val name = X500Name("CN=wavenet-node")
            val now = System.currentTimeMillis()
            val b = X509v3CertificateBuilder(name, BigInteger.ONE, Date(now - 3_600_000), Date(now + 365L * 86_400_000), name,
                SubjectPublicKeyInfoFactory.createSubjectPublicKeyInfo(key.generatePublicKey()))
            der = b.build(Ed25519ContentSigner(key)).encoded
        }
    }
    private val serverCred by lazy { ServerCred() }

    fun serverWrap(s: Socket, name: String, timeoutMs: Int = 5_000): Link = serverWrap(ByteChannel.of(s, name), timeoutMs)

    /** TLS 1.3 server over any byte channel (TCP, Bluetooth RFCOMM, pipe). */
    fun serverWrap(ch: ByteChannel, timeoutMs: Int = 5_000): Link {
        var binding: ByteArray? = null
        val proto = TlsServerProtocol(ch.input, ch.output)
        val server = object : DefaultTlsServer(bcCrypto) {
            override fun getSupportedVersions(): Array<ProtocolVersion> = ProtocolVersion.TLSv13.only()
            override fun getCredentials(): TlsCredentials {
                val cert = Certificate(TlsUtils.EMPTY_BYTES, arrayOf(CertificateEntry(bcCrypto.createCertificate(serverCred.der), null)))
                return BcDefaultTlsCredentialedSigner(TlsCryptoParameters(context), bcCrypto, serverCred.key, cert, SignatureAndHashAlgorithm.ed25519)
            }
            override fun notifyHandshakeComplete() {
                super.notifyHandshakeComplete()
                binding = context.exportKeyingMaterial(EXPORTER_LABEL, null, 32)
            }
        }
        try { ch.withTimeout(timeoutMs) { proto.accept(server) } } catch (e: Exception) { ch.close(); throw TransportError("TLS handshake failed: ${e.message}") }
        return StreamLink(ch, proto.inputStream, proto.outputStream, ch.name, binding ?: throw TransportError("no TLS exporter"))
    }
}

/** Adapter so bcpkix can sign the throw-away certificate with Ed25519 (standard algorithm, Bouncy Castle signer). */
private class Ed25519ContentSigner(key: Ed25519PrivateKeyParameters) : ContentSigner {
    private val signer = Ed25519Signer().apply { init(true, key) }
    private val buf = java.io.ByteArrayOutputStream()
    override fun getAlgorithmIdentifier() = AlgorithmIdentifier(EdECObjectIdentifiers.id_Ed25519)
    override fun getOutputStream(): OutputStream = buf
    override fun getSignature(): ByteArray { val d = buf.toByteArray(); signer.update(d, 0, d.size); return signer.generateSignature() }
}
