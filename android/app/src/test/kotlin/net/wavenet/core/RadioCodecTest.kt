package net.wavenet.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RadioCodecTest {
    private val id = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"

    @Test fun wifiDirectTxtRoundtrip() {
        val txt = WifiDirectTxt.build(id, 47801, "Pixel 8 من أحمد الطويل جداً جداً")
        val h = WifiDirectTxt.parse(txt)!!
        assertEquals("0123456789abcdef", h.nodeIdPrefix); assertEquals(47801, h.port); assertEquals(20, h.name.length)
    }

    @Test fun wifiDirectTxtRejectsGarbage() {
        assertNull(WifiDirectTxt.parse(null))
        assertNull(WifiDirectTxt.parse(mapOf("v" to "2", "id" to "0123456789abcdef", "p" to "1")))
        assertNull(WifiDirectTxt.parse(mapOf("v" to "1", "id" to "XYZ", "p" to "1")))
        assertNull(WifiDirectTxt.parse(mapOf("v" to "1", "id" to "0123456789abcdef", "p" to "70000")))
        assertNull(WifiDirectTxt.parse(mapOf("v" to "1", "id" to "0123456789abcdef")))
    }

    @Test fun blePayloadRoundtripAndSize() {
        val b = BlePayload.build(id, BlePayload.FLAG_SHARING or BlePayload.FLAG_BT_RFCOMM)
        assertEquals(10, b.size)
        assertTrue(2 + 16 + b.size <= 31, "must fit one BLE advertising packet with a 128-bit service UUID")
        val h = BlePayload.parse(b)!!
        assertEquals("0123456789abcdef", h.nodeIdPrefix); assertEquals(3, h.flags)
        assertNull(BlePayload.parse(byteArrayOf(2, 0, 0)))
        assertNull(BlePayload.parse(ByteArray(10)))
    }

    @Test fun uuidByteOrderQuirk() {
        val t = RadioIds.BT_RFCOMM_UUID
        assertTrue(UuidMatch.matches(t.uppercase(), t))
        assertTrue(UuidMatch.matches(UuidMatch.reversed(t), t))
        assertEquals(t, UuidMatch.reversed(UuidMatch.reversed(t)))
        assertTrue(!UuidMatch.matches(RadioIds.BLE_SERVICE_UUID, t))
    }

    @Test fun mediumRank() {
        assertEquals(listOf("tcp", "wifi-direct", "bluetooth", "x"), listOf("x", "bluetooth", "tcp", "wifi-direct").sortedBy { MediumRank.rank(it) })
    }
}
