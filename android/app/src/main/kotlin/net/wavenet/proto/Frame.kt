package net.wavenet.proto

/** WAVE-NP v1 message types (values identical to core/include/wavenet/protocol.hpp). */
object MsgType {
    const val NODE_ANNOUNCE = 0x0001; const val HELLO = 0x0010; const val HELLO_ACK = 0x0011; const val ERROR = 0x001F
    const val SEARCH_REQUEST = 0x0110; const val SEARCH_RESPONSE = 0x0111
    const val MANIFEST_REQUEST = 0x0120; const val MANIFEST_RESPONSE = 0x0121
    const val ROUTE_PROPOSAL = 0x0201; const val ROUTE_COMMIT = 0x0202
    const val CHUNK_REQUEST = 0x0300; const val DATA_CHUNK = 0x0301
}

class Frame(
    val type: Int,
    val networkId: Long,
    val src: ByteArray,                 // Ed25519 public key
    val payload: ByteArray,
    val dst: ByteArray = ByteArray(32),
    val requestId: Long = 0,
    val seq: Long = 0,
    val timestampMs: Long = System.currentTimeMillis(),
    val hopLimit: Int = 1,
    val flags: Int = 0,
) {
    val srcId: ByteArray get() = Crypto.sha256(src)

    companion object {
        const val MAGIC = 0x504E5657L
        const val VERSION = 1
        const val BASE_HEADER_LEN = 107
        const val MAX_HEADER_LEN = 1024
        const val MAX_PAYLOAD = (1 shl 20) + (64 shl 10)
        const val SIG_LEN = 64
        const val MAX_FRAME = MAX_HEADER_LEN + MAX_PAYLOAD + SIG_LEN
        private const val DOMAIN = "WAVE-NP/1 frame"

        private fun signingInput(body: ByteArray, bodyLen: Int, context: ByteArray): ByteArray =
            Writer().str(DOMAIN).blob(context).raw(if (bodyLen == body.size) body else body.copyOf(bodyLen)).bytes()

        fun encode(f: Frame, signer: Identity, context: ByteArray = ByteArray(0)): ByteArray {
            if (f.payload.size > MAX_PAYLOAD) throw IllegalArgumentException("payload too large")
            require(f.src.contentEquals(signer.publicKey)) { "header src != signer" }
            val w = Writer().u32(MAGIC).u8(VERSION).u16(f.type).u8(f.flags).u16(BASE_HEADER_LEN).u32(f.networkId)
                .raw(f.src).raw(f.dst).u64(f.requestId).u64(f.seq).u64(f.timestampMs).u8(f.hopLimit)
                .u32(f.payload.size.toLong()).raw(f.payload)
            val body = w.bytes()
            return Writer().raw(body).raw(signer.sign(signingInput(body, body.size, context))).bytes()
        }

        /** Total frame size from the first BASE_HEADER_LEN bytes; throws DecodeError if invalid. */
        fun peekSize(p: ByteArray): Int {
            val r = Reader(p)
            if (r.u32() != MAGIC) throw DecodeError("bad magic")
            if (r.u8() != VERSION) throw DecodeError("unsupported version")
            r.u16(); r.u8()
            val hl = r.u16()
            if (hl < BASE_HEADER_LEN || hl > MAX_HEADER_LEN) throw DecodeError("bad header length")
            val pl = Reader(p, BASE_HEADER_LEN - 4).u32()
            if (pl > MAX_PAYLOAD) throw DecodeError("payload too large")
            return hl + pl.toInt() + SIG_LEN
        }

        fun decode(wire: ByteArray, context: ByteArray = ByteArray(0)): Frame {
            if (wire.size > MAX_FRAME) throw DecodeError("frame too large")
            if (wire.size < BASE_HEADER_LEN + SIG_LEN) throw DecodeError("frame too short")
            val r = Reader(wire)
            if (r.u32() != MAGIC) throw DecodeError("bad magic")
            if (r.u8() != VERSION) throw DecodeError("unsupported version")
            val type = r.u16(); val flags = r.u8(); val hl = r.u16()
            if (hl < BASE_HEADER_LEN || hl > MAX_HEADER_LEN) throw DecodeError("bad header length")
            val net = r.u32(); val src = r.raw(32); val dst = r.raw(32)
            val req = r.u64(); val seq = r.u64(); val ts = r.u64(); val hop = r.u8()
            val pl = r.u32()
            if (pl > MAX_PAYLOAD) throw DecodeError("payload too large")
            r.raw(hl - BASE_HEADER_LEN)
            if (r.remaining.toLong() != pl + SIG_LEN) throw DecodeError("length mismatch")
            val payload = r.raw(pl.toInt())
            val sig = r.raw(SIG_LEN)
            if (!Crypto.verify(src, signingInput(wire, wire.size - SIG_LEN, context), sig)) throw DecodeError("bad signature")
            return Frame(type, net, src, payload, dst, req, seq, ts, hop, flags)
        }
    }
}
