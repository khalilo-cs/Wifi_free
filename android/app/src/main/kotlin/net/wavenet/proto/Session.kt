package net.wavenet.proto

class SessionError(msg: String) : Exception(msg)

/** Trust policy (mirror of C++ TrustPolicy). */
class TrustPolicy(val allowlistMode: Boolean = false, val allow: Set<String> = emptySet(), val block: Set<String> = emptySet()) {
    fun permits(nodeIdHex: String) = nodeIdHex !in block && (!allowlistMode || nodeIdHex in allow)
}

/** Mutually authenticated WAVE-NP session; byte-for-byte the same handshake as core/src/session.cpp. */
class Session private constructor(val link: Link, private val me: Identity, private val net: Long) {
    lateinit var peerKey: ByteArray; private set
    val peerIdHex: String get() = Hex.encode(Crypto.sha256(peerKey))
    private val binding = link.channelBinding
    private lateinit var myNonce: ByteArray
    private lateinit var peerNonce: ByteArray
    private var sendSeq = 0L
    private var recvSeq = 0L

    private fun ctx(nonce: ByteArray) = nonce + binding
    private fun make(t: Int, p: ByteArray, req: Long) = Frame(t, net, me.publicKey, p, requestId = req, seq = ++sendSeq)

    @Synchronized fun send(type: Int, payload: ByteArray, requestId: Long = 0) = link.sendFrame(Frame.encode(make(type, payload, requestId), me, ctx(peerNonce)))

    fun recv(timeoutMs: Int): Frame {
        val wire = link.recvFrame(timeoutMs)
        val f = try { Frame.decode(wire, ctx(myNonce)) } catch (e: DecodeError) { link.close(); throw SessionError("invalid frame: ${e.message}") }
        if (!f.src.contentEquals(peerKey)) { link.close(); throw SessionError("frame from unexpected key") }
        if (f.seq.toULong() <= recvSeq.toULong()) { link.close(); throw SessionError("replayed or reordered frame") }
        if (f.networkId != net) { link.close(); throw SessionError("network id mismatch") }
        recvSeq = f.seq
        return f
    }

    fun close() = link.close()

    companion object {
        const val MAX_SKEW_MS = 5 * 60 * 1000L
        const val HANDSHAKE_TIMEOUT_MS = 5000
        private fun skewOk(ts: Long) = Math.abs(System.currentTimeMillis() - ts) <= MAX_SKEW_MS

        fun initiate(link: Link, me: Identity, net: Long, trust: TrustPolicy): Session {
            val s = Session(link, me, net)
            s.myNonce = Crypto.random(32)
            link.sendFrame(Frame.encode(s.make(MsgType.HELLO, s.myNonce, 0), me, s.binding))
            val ack = try { Frame.decode(link.recvFrame(HANDSHAKE_TIMEOUT_MS), s.ctx(s.myNonce)) }
                catch (e: DecodeError) { link.close(); throw SessionError("handshake rejected: ${e.message}") }
            if (ack.type == MsgType.ERROR) { link.close(); throw SessionError("peer refused: " + String(ack.payload, Charsets.UTF_8)) }
            if (ack.type != MsgType.HELLO_ACK || ack.payload.size != 32 || ack.networkId != net || !skewOk(ack.timestampMs)) {
                link.close(); throw SessionError("invalid HELLO_ACK")
            }
            if (!trust.permits(Hex.encode(ack.srcId))) { link.close(); throw SessionError("peer not trusted") }
            if (ack.src.contentEquals(me.publicKey)) { link.close(); throw SessionError("connected to self") }
            s.peerKey = ack.src; s.peerNonce = ack.payload; s.recvSeq = ack.seq
            return s
        }

        fun respond(link: Link, me: Identity, net: Long, trust: TrustPolicy): Session {
            val s = Session(link, me, net)
            val hello = try { Frame.decode(link.recvFrame(HANDSHAKE_TIMEOUT_MS), s.binding) }
                catch (e: DecodeError) { link.close(); throw SessionError("bad HELLO: ${e.message}") }
            if (hello.type != MsgType.HELLO || hello.payload.size != 32) { link.close(); throw SessionError("expected HELLO") }
            s.myNonce = Crypto.random(32)
            fun refuse(why: String): Nothing {
                try { link.sendFrame(Frame.encode(s.make(MsgType.ERROR, why.toByteArray(), 0), me, s.ctx(hello.payload))) } catch (e: Exception) { }
                link.close(); throw SessionError(why)
            }
            if (hello.networkId != net) refuse("network id mismatch")
            if (!skewOk(hello.timestampMs)) refuse("clock skew too large")
            if (!trust.permits(Hex.encode(hello.srcId))) refuse("not trusted")
            if (hello.src.contentEquals(me.publicKey)) refuse("self connection")
            s.peerKey = hello.src; s.peerNonce = hello.payload; s.recvSeq = hello.seq
            link.sendFrame(Frame.encode(s.make(MsgType.HELLO_ACK, s.myNonce, 0), me, s.ctx(s.peerNonce)))
            return s
        }
    }
}
