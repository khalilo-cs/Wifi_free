package net.wavenet.core

/** Connection media WAVE-NET knows about. `waveTransport` = whether WAVE data transfer over it is implemented. */
enum class RadioKind(val feature: String?, val minSdk: Int, val waveTransport: Boolean) {
    WIFI_LAN("android.hardware.wifi", 1, true),         // same protocol as the Linux node (TCP + TLS)
    WIFI_DIRECT("android.hardware.wifi.direct", 14, true),  // IP link once the P2P group is formed
    WIFI_AWARE("android.hardware.wifi.aware", 26, false),
    BLE("android.hardware.bluetooth_le", 18, false),        // discovery beacon only (too slow for files)
    BT_CLASSIC("android.hardware.bluetooth", 1, true),     // RFCOMM byte stream
}

enum class RadioState { UNSUPPORTED, NEEDS_PERMISSION, OFF, READY, DISCOVERY_ONLY, PLANNED }

data class RadioStatus(val kind: RadioKind, val state: RadioState, val missingPermissions: List<String>)

object Permissions {
    const val FINE_LOCATION = "android.permission.ACCESS_FINE_LOCATION"
    const val BLUETOOTH_SCAN = "android.permission.BLUETOOTH_SCAN"            // API 31+
    const val BLUETOOTH_CONNECT = "android.permission.BLUETOOTH_CONNECT"      // API 31+
    const val NEARBY_WIFI = "android.permission.NEARBY_WIFI_DEVICES"          // API 33+
    const val BLUETOOTH_ADVERTISE = "android.permission.BLUETOOTH_ADVERTISE"  // API 31+ (BLE beacon, discoverable)

    /**
     * True when the manifest can assert `neverForLocation` (needs an API 31+ build SDK). The current build uses the
     * Debian API 23 android.jar, so it is false: location is then also required on Android 12+ (see D18).
     */
    const val NEVER_FOR_LOCATION_DECLARED = false

    /** Runtime permissions needed to *discover* peers on this medium for a given Android version. */
    fun forDiscovery(kind: RadioKind, sdk: Int, neverForLocation: Boolean = NEVER_FOR_LOCATION_DECLARED): List<String> {
        val loc = if (neverForLocation) emptyList() else listOf(FINE_LOCATION)
        return when (kind) {
            RadioKind.WIFI_LAN -> emptyList()  // INTERNET / multicast are install-time permissions
            RadioKind.BLE, RadioKind.BT_CLASSIC ->
                if (sdk >= 31) listOf(BLUETOOTH_SCAN, BLUETOOTH_CONNECT, BLUETOOTH_ADVERTISE) + loc else listOf(FINE_LOCATION)
            RadioKind.WIFI_DIRECT, RadioKind.WIFI_AWARE ->
                if (sdk >= 33) listOf(NEARBY_WIFI) + loc else listOf(FINE_LOCATION)
        }
    }
}

object CapabilityDetector {
    /**
     * Pure function so it can be unit-tested without a device.
     * @param features   system features reported by PackageManager
     * @param granted    whether a runtime permission is granted
     * @param enabled    whether the radio is currently switched on (null = unknown/not applicable)
     */
    fun detect(features: Set<String>, sdk: Int, granted: (String) -> Boolean,
               enabled: (RadioKind) -> Boolean?): List<RadioStatus> = RadioKind.values().map { k ->
        val supported = sdk >= k.minSdk && (k.feature == null || k.feature in features)
        val missing = if (supported) Permissions.forDiscovery(k, sdk).filterNot(granted) else emptyList()
        val state = when {
            !supported -> RadioState.UNSUPPORTED
            missing.isNotEmpty() -> RadioState.NEEDS_PERMISSION
            enabled(k) == false -> RadioState.OFF
            k == RadioKind.BLE -> RadioState.DISCOVERY_ONLY
            !k.waveTransport -> RadioState.PLANNED
            else -> RadioState.READY
        }
        RadioStatus(k, state, missing)
    }

    /** Order in which media should be tried for bulk transfer (fastest first). Only media that are READY. */
    fun bulkTransferOrder(statuses: List<RadioStatus>): List<RadioKind> {
        val rank = listOf(RadioKind.WIFI_LAN, RadioKind.WIFI_DIRECT, RadioKind.WIFI_AWARE, RadioKind.BT_CLASSIC)
        return rank.filter { k -> statuses.any { it.kind == k && it.state == RadioState.READY } }
    }
}
