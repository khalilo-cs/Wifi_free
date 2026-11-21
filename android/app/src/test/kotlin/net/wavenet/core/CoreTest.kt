package net.wavenet.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CapabilityTest {
    private val allFeatures = RadioKind.values().mapNotNull { it.feature }.toSet()

    @Test fun phoneWithoutAwareOrBle() {
        val st = CapabilityDetector.detect(setOf("android.hardware.wifi", "android.hardware.wifi.direct"), 34, { true }, { true })
        val m = st.associateBy { it.kind }
        assertEquals(RadioState.READY, m[RadioKind.WIFI_LAN]!!.state)
        assertEquals(RadioState.READY, m[RadioKind.WIFI_DIRECT]!!.state)
        assertEquals(RadioState.UNSUPPORTED, m[RadioKind.WIFI_AWARE]!!.state)
        assertEquals(RadioState.UNSUPPORTED, m[RadioKind.BLE]!!.state)
    }

    @Test fun permissionsDependOnAndroidVersion() {
        assertEquals(listOf(Permissions.FINE_LOCATION), Permissions.forDiscovery(RadioKind.BLE, 30))
        // With neverForLocation declared (modern SDK): no location on 31+/33+
        assertEquals(listOf(Permissions.BLUETOOTH_SCAN, Permissions.BLUETOOTH_CONNECT, Permissions.BLUETOOTH_ADVERTISE), Permissions.forDiscovery(RadioKind.BLE, 31, true))
        assertEquals(listOf(Permissions.NEARBY_WIFI), Permissions.forDiscovery(RadioKind.WIFI_DIRECT, 33, true))
        // Current build (flag cannot be declared, D18): location is required too
        assertEquals(listOf(Permissions.BLUETOOTH_SCAN, Permissions.BLUETOOTH_CONNECT, Permissions.BLUETOOTH_ADVERTISE, Permissions.FINE_LOCATION), Permissions.forDiscovery(RadioKind.BLE, 31))
        assertEquals(listOf(Permissions.NEARBY_WIFI, Permissions.FINE_LOCATION), Permissions.forDiscovery(RadioKind.WIFI_DIRECT, 33))
        assertEquals(listOf(Permissions.FINE_LOCATION), Permissions.forDiscovery(RadioKind.WIFI_DIRECT, 32))
        assertTrue(Permissions.forDiscovery(RadioKind.WIFI_LAN, 34).isEmpty())
    }

    @Test fun missingPermissionAndRadioOff() {
        val st = CapabilityDetector.detect(allFeatures, 34, { it != Permissions.BLUETOOTH_SCAN },
            { k -> if (k == RadioKind.WIFI_LAN) false else true }).associateBy { it.kind }
        assertEquals(RadioState.NEEDS_PERMISSION, st[RadioKind.BLE]!!.state)
        assertEquals(listOf(Permissions.BLUETOOTH_SCAN), st[RadioKind.BLE]!!.missingPermissions)
        assertEquals(RadioState.OFF, st[RadioKind.WIFI_LAN]!!.state)
    }

    @Test fun awareNeedsApi26() {
        val st = CapabilityDetector.detect(allFeatures, 25, { true }, { true }).associateBy { it.kind }
        assertEquals(RadioState.UNSUPPORTED, st[RadioKind.WIFI_AWARE]!!.state)
    }

    @Test fun bulkOrderOnlyUsesReadyMedia() {
        val st = CapabilityDetector.detect(allFeatures, 34, { true }, { true })
        assertEquals(listOf(RadioKind.WIFI_LAN, RadioKind.WIFI_DIRECT, RadioKind.BT_CLASSIC), CapabilityDetector.bulkTransferOrder(st))  // Aware not implemented; BLE discovery-only
        assertEquals(RadioState.DISCOVERY_ONLY, st.first { it.kind == RadioKind.BLE }.state)
        val off = CapabilityDetector.detect(allFeatures, 34, { true }, { false })
        assertTrue(CapabilityDetector.bulkTransferOrder(off).isEmpty())
    }
}

class PolicyTest {
    private val ok = DeviceState(batteryPercent = 80, charging = false, onMeteredNetwork = false, mobileDataUsedTodayMb = 0, storageUsedForOthersMb = 0)
    private val on = ResourcePolicy(sharingEnabled = true, allowRelay = true, allowCache = true)

    @Test fun defaultsShareNothing() = assertEquals(Decision.DISABLED, PolicyEngine.evaluate(ResourcePolicy(), ok, Role.HOST))
    @Test fun allowedWhenEnabled() = assertEquals(Decision.ALLOWED, PolicyEngine.evaluate(on, ok, Role.HOST))
    @Test fun roleSwitches() {
        assertEquals(Decision.ROLE_OFF, PolicyEngine.evaluate(on.copy(allowRelay = false), ok, Role.RELAY))
        assertEquals(Decision.ROLE_OFF, PolicyEngine.evaluate(on.copy(allowCache = false), ok, Role.CACHE))
    }
    @Test fun battery() {
        assertEquals(Decision.LOW_BATTERY, PolicyEngine.evaluate(on, ok.copy(batteryPercent = 20), Role.HOST))
        assertEquals(Decision.ALLOWED, PolicyEngine.evaluate(on, ok.copy(batteryPercent = 20, charging = true), Role.HOST))
    }
    @Test fun meteredData() {
        val m = ok.copy(onMeteredNetwork = true, mobileDataUsedTodayMb = 50)
        assertEquals(Decision.METERED, PolicyEngine.evaluate(on, m, Role.HOST))
        assertEquals(Decision.DATA_CAP, PolicyEngine.evaluate(on.copy(allowMetered = true, maxMobileDataMbPerDay = 50), m, Role.HOST))
        assertEquals(Decision.ALLOWED, PolicyEngine.evaluate(on.copy(allowMetered = true, maxMobileDataMbPerDay = 51), m, Role.HOST))
    }
    @Test fun storageCapOnlyForCache() {
        val full = ok.copy(storageUsedForOthersMb = 500)
        assertEquals(Decision.STORAGE_CAP, PolicyEngine.evaluate(on, full, Role.CACHE))
        assertEquals(Decision.ALLOWED, PolicyEngine.evaluate(on, full, Role.RELAY))
    }
    @Test fun invalidValuesClamped() {
        val p = ResourcePolicy(minBatteryPercent = -7, maxStorageMb = -1).validated()
        assertEquals(5, p.minBatteryPercent); assertEquals(0, p.maxStorageMb)
    }
}

class StringsTest {
    private fun keys(path: String) = Regex("""<string name="([a-z_]+)"""").findAll(File(path).readText()).map { it.groupValues[1] }.toSet()

    @Test fun arabicAndEnglishHaveSameKeys() {
        val en = keys("src/main/res/values/strings.xml"); val ar = keys("src/main/res/values-ar/strings.xml")
        assertTrue(en.size > 40)
        assertEquals(en, ar)
    }

    @Test fun everyDecisionAndStateHasAString() {
        val en = keys("src/main/res/values/strings.xml")
        for (d in Decision.values()) assertTrue("decision_" + d.name.lowercase() in en, "missing string for $d")
        for (s in RadioState.values()) assertTrue("status_" + s.name.lowercase() in en, "missing string for $s")
    }
}
