package net.wavenet.app

import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import net.wavenet.core.CapabilityDetector
import net.wavenet.core.DeviceState
import net.wavenet.core.RadioKind
import net.wavenet.core.RadioStatus

/** Hardware abstraction: reads what THIS device supports. Unsupported media are reported, never assumed. */
object DeviceProbe {
    fun radios(ctx: Context): List<RadioStatus> {
        val pm = ctx.packageManager
        val features = RadioKind.values().mapNotNull { it.feature }.filter { pm.hasSystemFeature(it) }.toSet()
        val granted = { p: String -> ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED }
        return CapabilityDetector.detect(features, Build.VERSION.SDK_INT, granted) { k -> enabled(ctx, k) }
    }

    private fun enabled(ctx: Context, k: RadioKind): Boolean? = try {
        when (k) {
            RadioKind.WIFI_LAN, RadioKind.WIFI_DIRECT, RadioKind.WIFI_AWARE ->
                (ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager)?.isWifiEnabled
            RadioKind.BLE, RadioKind.BT_CLASSIC ->
                (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter?.isEnabled
        }
    } catch (e: SecurityException) { null }

    fun state(ctx: Context): DeviceState {
        val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val level = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 100
        val sticky: Intent? = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val plugged = (sticky?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        // Mobile-data / storage accounting is not implemented yet: reported as 0 (no transfers exist on Android yet).
        return DeviceState(batteryPercent = level, charging = plugged, onMeteredNetwork = cm?.isActiveNetworkMetered ?: true,
                           mobileDataUsedTodayMb = 0, storageUsedForOthersMb = 0)
    }
}
