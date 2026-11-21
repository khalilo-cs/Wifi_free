package net.wavenet.app

import android.content.Context
import net.wavenet.core.ResourcePolicy

/** Local settings (SharedPreferences). Nothing here is secret. */
class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("wavenet", Context.MODE_PRIVATE)

    var welcomed: Boolean
        get() = sp.getBoolean("welcomed", false)
        set(v) { sp.edit().putBoolean("welcomed", v).apply() }

    /** User enabled Bluetooth / Wi-Fi Direct (permissions granted). */
    var radios: Boolean
        get() = sp.getBoolean("radios", false)
        set(v) { sp.edit().putBoolean("radios", v).apply() }

    /** "" = follow system, otherwise "ar" or "en". */
    var language: String
        get() = sp.getString("language", "") ?: ""
        set(v) { sp.edit().putString("language", v).apply() }

    var policy: ResourcePolicy
        get() = ResourcePolicy(
            sharingEnabled = sp.getBoolean("sharing", false),
            minBatteryPercent = sp.getInt("min_battery", 30),
            maxMobileDataMbPerDay = sp.getInt("max_data", 0),
            maxStorageMb = sp.getInt("max_storage", 500),
            allowRelay = sp.getBoolean("relay", false),
            allowCache = sp.getBoolean("cache", false),
            allowMetered = sp.getBoolean("metered", false),
        )
        set(p0) {
            val p = p0.validated()
            sp.edit().putBoolean("sharing", p.sharingEnabled).putInt("min_battery", p.minBatteryPercent)
                .putInt("max_data", p.maxMobileDataMbPerDay).putInt("max_storage", p.maxStorageMb)
                .putBoolean("relay", p.allowRelay).putBoolean("cache", p.allowCache)
                .putBoolean("metered", p.allowMetered).apply()
        }
}
