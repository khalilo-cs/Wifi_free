package net.wavenet.proto

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProtoTest {
    @Test fun ed25519Rfc8032Vector() {  // RFC 8032 §7.1 TEST 1
        val pk = Hex.decode("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a")
        val sig = Hex.decode("e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b")
        assertTrue(Crypto.verify(pk, ByteArray(0), sig))
        assertFalse(Crypto.verify(pk, byteArrayOf(1), sig))
        val id = Identity.fromSeed(Hex.decode("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60"))
        assertEquals(Hex.encode(pk), Hex.encode(id.publicKey))
        assertEquals(Hex.encode(sig), Hex.encode(id.sign(ByteArray(0))))
    }

    @Test fun sha256Vector() = assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", Hex.encode(Crypto.sha256("abc".toByteArray())))

    @Test fun frameRoundtripAndTamper() {
        val id = Identity.generate()
        val f = Frame(MsgType.SEARCH_REQUEST, 7, id.publicKey, byteArrayOf(1, 2, 3), requestId = 42, seq = 3, timestampMs = 1234, hopLimit = 4)
        val w = Frame.encode(f, id)
        assertEquals(Frame.BASE_HEADER_LEN + 3 + 64, w.size)
        assertEquals(w.size, Frame.peekSize(w))
        val d = Frame.decode(w)
        assertEquals(42L, d.requestId); assertEquals(3L, d.seq); assertEquals(7L, d.networkId); assertEquals(4, d.hopLimit)
        var accepted = 0
        for (i in w.indices) for (bit in 0 until 8) {
            val c = w.copyOf(); c[i] = (c[i].toInt() xor (1 shl bit)).toByte()
            try { Frame.decode(c); accepted++ } catch (e: DecodeError) { }
        }
        assertEquals(0, accepted)
        val ctx = ByteArray(32) { 0xAA.toByte() }
        val bound = Frame.encode(f, id, ctx)
        Frame.decode(bound, ctx)
        assertFailsWith<DecodeError> { Frame.decode(bound) }
    }

    @Test fun manifestSignAndValidate() {
        val id = Identity.generate()
        val m = Manifest(Crypto.sha256(byteArrayOf(9)), 1, Manifest.MIN_CHUNK, listOf(ByteArray(32)), title = "كتاب", fileName = "a.txt",
                         publisher = id.publicKey, createdMs = 5).signedBy(id)
        val d = Manifest.decode(m.encode())
        assertEquals("كتاب", d.title)
        val enc = m.encode(); enc[40] = (enc[40].toInt() xor 1).toByte()
        assertFailsWith<DecodeError> { Manifest.decode(enc) }
        val bad = Manifest(m.contentId, 1, Manifest.MIN_CHUNK, listOf(ByteArray(32)), title = "x", fileName = "../x", publisher = id.publicKey, createdMs = 5).signedBy(id)
        assertFailsWith<DecodeError> { Manifest.decode(bad.encode()) }
    }
}
