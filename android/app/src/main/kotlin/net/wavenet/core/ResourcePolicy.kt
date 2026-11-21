package net.wavenet.core

/** Roles a device may play for OTHER devices. Downloading for yourself is never limited by this policy. */
enum class Role { HOST, RELAY, CACHE }

/** User-chosen limits. Defaults are conservative: sharing is OFF until the user enables it. */
data class ResourcePolicy(
    val sharingEnabled: Boolean = false,
    val minBatteryPercent: Int = 30,
    val maxMobileDataMbPerDay: Int = 0,
    val maxStorageMb: Int = 500,
    val allowRelay: Boolean = false,
    val allowCache: Boolean = false,
    val allowMetered: Boolean = false,
) {
    fun validated(): ResourcePolicy = copy(
        minBatteryPercent = minBatteryPercent.coerceIn(5, 100),
        maxMobileDataMbPerDay = maxMobileDataMbPerDay.coerceIn(0, 100_000),
        maxStorageMb = maxStorageMb.coerceIn(0, 1_000_000),
    )
}

data class DeviceState(
    val batteryPercent: Int,
    val charging: Boolean,
    val onMeteredNetwork: Boolean,
    val mobileDataUsedTodayMb: Int,
    val storageUsedForOthersMb: Int,
)

enum class Decision { ALLOWED, DISABLED, ROLE_OFF, LOW_BATTERY, METERED, DATA_CAP, STORAGE_CAP }

object PolicyEngine {
    /** Decides whether this device may currently act in `role` for others. First failing rule wins. */
    fun evaluate(p0: ResourcePolicy, s: DeviceState, role: Role): Decision {
        val p = p0.validated()
        if (!p.sharingEnabled) return Decision.DISABLED
        if (role == Role.RELAY && !p.allowRelay) return Decision.ROLE_OFF
        if (role == Role.CACHE && !p.allowCache) return Decision.ROLE_OFF
        if (!s.charging && s.batteryPercent < p.minBatteryPercent) return Decision.LOW_BATTERY
        if (s.onMeteredNetwork) {
            if (!p.allowMetered) return Decision.METERED
            if (s.mobileDataUsedTodayMb >= p.maxMobileDataMbPerDay) return Decision.DATA_CAP
        }
        if (role == Role.CACHE && s.storageUsedForOthersMb >= p.maxStorageMb) return Decision.STORAGE_CAP
        return Decision.ALLOWED
    }
}
