package net.wavenet.core

/** Fixed identifiers of the WAVE-NET radio transports (random UUIDs generated for this project). */
object RadioIds {
    const val BT_RFCOMM_UUID = "6f1c2b9e-4b7a-4a3c-9d3e-57415645b001"   // Bluetooth Classic RFCOMM service "WAVE-NET"
    const val BLE_SERVICE_UUID = "6f1c2b9e-4b7a-4a3c-9d3e-57415645b002" // BLE beacon (discovery hint only)
    const val DNSSD_TYPE = "_wavenet._tcp"                               // Wi-Fi Direct service discovery
    const val BT_SERVICE_NAME = "WAVE-NET"
}

/** What a radio beacon / service record tells us before connecting. Never trusted: the TLS+WAVE handshake authenticates. */
data class PeerHint(val nodeIdPrefix: String, val port: Int, val name: String, val flags: Int = 0)

object WifiDirectTxt {
    fun build(nodeIdHex: String, port: Int, name: String): Map<String, String> =
        linkedMapOf("v" to "1", "id" to nodeIdHex.take(16).lowercase(), "p" to port.toString(), "n" to name.take(20))

    fun parse(txt: Map<String, String>?): PeerHint? {
        if (txt == null || txt["v"] != "1") return null
        val id = txt["id"] ?: return null
        if (id.length != 16 || !id.all { it in '0'..'9' || it in 'a'..'f' }) return null
        val port = txt["p"]?.toIntOrNull() ?: return null
        if (port !in 1..65535) return null
        return PeerHint(id, port, (txt["n"] ?: "").take(20))
    }
}

/** BLE scan-response service data: version(1) | node-id prefix(8) | flags(1). Fits the 31-byte advertising limit. */
object BlePayload {
    const val FLAG_SHARING = 1; const val FLAG_BT_RFCOMM = 2; const val FLAG_WIFI_DIRECT = 4
    fun build(nodeIdHex: String, flags: Int): ByteArray {
        val id = nodeIdHex.take(16)
        require(id.length == 16) { "node id too short" }
        return byteArrayOf(1) + ByteArray(8) { i -> id.substring(2 * i, 2 * i + 2).toInt(16).toByte() } + byteArrayOf(flags.toByte())
    }
    fun parse(b: ByteArray?): PeerHint? {
        if (b == null || b.size != 10 || b[0].toInt() != 1) return null
        val hex = StringBuilder(); for (i in 1..8) hex.append(String.format("%02x", b[i].toInt() and 0xff))
        return PeerHint(hex.toString(), 0, "", b[9].toInt() and 0xff)
    }
}

object UuidMatch {
    /** Some Android versions report SDP UUIDs with reversed byte order; accept both forms. */
    fun matches(candidate: String, target: String): Boolean {
        val c = candidate.lowercase(); val t = target.lowercase()
        return c == t || c == reversed(t)
    }
    fun reversed(uuid: String): String {
        val hex = uuid.replace("-", "")
        val bytes = (0 until 16).map { hex.substring(2 * it, 2 * it + 2) }.reversed().joinToString("")
        return "${bytes.substring(0, 8)}-${bytes.substring(8, 12)}-${bytes.substring(12, 16)}-${bytes.substring(16, 20)}-${bytes.substring(20)}"
    }
}

/** Expected-speed rank of media (lower = try first). Real choice is made by the downloader's work-stealing queue. */
object MediumRank {
    private val order = listOf("tcp", "wifi-direct", "bluetooth")
    fun rank(medium: String): Int = order.indexOf(medium).let { if (it < 0) order.size else it }
}
