package net.wavenet.app

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.text.InputType
import java.util.Locale
import net.wavenet.core.Decision
import net.wavenet.core.PolicyEngine
import net.wavenet.core.RadioKind
import net.wavenet.core.RadioState
import net.wavenet.core.Role

/** Single-activity UI built in code (no AndroidX available in the build environment). */
class MainActivity : Activity() {
    private lateinit var prefs: Prefs
    private lateinit var content: LinearLayout
    private var scanner: NearbyScanner? = null
    private var tab = 0

    override fun attachBaseContext(base: Context) {
        val lang = Prefs(base).language
        if (lang.isEmpty()) { super.attachBaseContext(base); return }
        val cfg = android.content.res.Configuration(base.resources.configuration)
        val loc = Locale(lang)
        Locale.setDefault(loc)
        cfg.setLocale(loc)
        cfg.setLayoutDirection(loc)
        super.attachBaseContext(base.createConfigurationContext(cfg))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        tab = savedInstanceState?.getInt("tab") ?: 0
        if (!prefs.welcomed) showWelcome() else showMain()
    }

    override fun onSaveInstanceState(out: Bundle) { super.onSaveInstanceState(out); out.putInt("tab", tab) }
    override fun onPause() { super.onPause(); scanner?.stop() }  // never scan in the background
    override fun onResume() { super.onResume(); if (prefs.welcomed && ::content.isInitialized) render() }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun s(id: Int) = getString(id)

    private fun text(t: String, size: Float = 15f, bold: Boolean = false, color: Int? = null) = TextView(this).apply {
        text = t; textSize = size; if (bold) setTypeface(typeface, Typeface.BOLD)
        color?.let { setTextColor(it) }
        setPadding(0, dp(6), 0, dp(6)); textAlignment = View.TEXT_ALIGNMENT_VIEW_START
    }

    private fun showWelcome() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setGravity(Gravity.CENTER); setPadding(dp(28), dp(28), dp(28), dp(28)) }
        box.addView(text(s(R.string.app_name), 30f, true, getColor(R.color.wave_primary)).apply { gravity = Gravity.CENTER })
        box.addView(text(s(R.string.welcome_title), 20f, true).apply { gravity = Gravity.CENTER })
        box.addView(text(s(R.string.welcome_body), 16f).apply { gravity = Gravity.CENTER })
        box.addView(Button(this).apply { text = s(R.string.welcome_continue); setOnClickListener { prefs.welcomed = true; showMain() } })
        setContentView(box)
    }

    private fun showMain() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(text(s(R.string.app_name), 20f, true, 0xFFFFFFFF.toInt()).apply {
            setBackgroundColor(getColor(R.color.wave_primary)); setPadding(dp(16), dp(14), dp(16), dp(14)) })
        val scroll = ScrollView(this)
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(16)) }
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        val nav = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(R.string.tab_dashboard, R.string.tab_nearby, R.string.tab_resources, R.string.tab_settings).forEachIndexed { i, id ->
            nav.addView(Button(this).apply { text = s(id); textSize = 12f; setOnClickListener { tab = i; scanner?.stop(); render() } },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        root.addView(nav)
        setContentView(root)
        render()
    }

    private fun render() {
        content.removeAllViews()
        when (tab) { 0 -> dashboard(); 1 -> nearby(); 2 -> resourcesScreen(); else -> settings() }
    }

    private fun radioName(k: RadioKind) = s(when (k) {
        RadioKind.WIFI_LAN -> R.string.radio_wifi_lan; RadioKind.WIFI_DIRECT -> R.string.radio_wifi_direct
        RadioKind.WIFI_AWARE -> R.string.radio_wifi_aware; RadioKind.BLE -> R.string.radio_ble
        RadioKind.BT_CLASSIC -> R.string.radio_bt_classic })

    private fun stateText(st: RadioState) = s(when (st) {
        RadioState.UNSUPPORTED -> R.string.status_unsupported; RadioState.NEEDS_PERMISSION -> R.string.status_needs_permission
        RadioState.OFF -> R.string.status_off; RadioState.READY -> R.string.status_ready; RadioState.PLANNED -> R.string.status_planned })

    private fun stateColor(st: RadioState) = getColor(when (st) {
        RadioState.READY -> R.color.wave_ok; RadioState.UNSUPPORTED -> R.color.wave_off; else -> R.color.wave_warn })

    private fun decisionText(d: Decision) = s(when (d) {
        Decision.ALLOWED -> R.string.decision_allowed; Decision.DISABLED -> R.string.decision_disabled
        Decision.ROLE_OFF -> R.string.decision_role_off; Decision.LOW_BATTERY -> R.string.decision_low_battery
        Decision.METERED -> R.string.decision_metered; Decision.DATA_CAP -> R.string.decision_data_cap
        Decision.STORAGE_CAP -> R.string.decision_storage_cap })

    private fun dashboard() {
        content.addView(text(s(R.string.section_radios), 17f, true))
        for (r in DeviceProbe.radios(this)) {
            content.addView(text(radioName(r.kind), 15f, true))
            content.addView(text(stateText(r.state), 14f, color = stateColor(r.state)))
        }
        content.addView(text(s(R.string.node_identity), 17f, true))
        content.addView(text(s(R.string.identity_pending), 14f))
        content.addView(text(s(R.string.res_current), 17f, true))
        content.addView(text(decisionText(PolicyEngine.evaluate(prefs.policy, DeviceProbe.state(this), Role.HOST)), 14f))
    }

    private fun nearby() {
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val status = text(s(R.string.nearby_none), 14f)
        content.addView(Button(this).apply {
            text = s(R.string.nearby_scan)
            setOnClickListener {
                val needed = DeviceProbe.radios(this@MainActivity)
                    .filter { it.kind == RadioKind.BLE || it.kind == RadioKind.WIFI_DIRECT }
                    .flatMap { it.missingPermissions }.distinct()
                if (needed.isNotEmpty()) { requestPermissions(needed.toTypedArray(), REQ_SCAN); return@setOnClickListener }
                startScan(list, status)
            }
        })
        content.addView(status)
        content.addView(list)
        content.addView(text(s(R.string.nearby_note), 13f, color = getColor(R.color.wave_warn)))
    }

    private fun startScan(list: LinearLayout, status: TextView) {
        status.text = s(R.string.nearby_scanning)
        list.removeAllViews()
        scanner?.stop()
        scanner = NearbyScanner(this) { items ->
            list.removeAllViews()
            items.forEach { list.addView(text(it, 14f)) }
            status.text = if (items.isEmpty()) s(R.string.nearby_none) else items.size.toString()
        }.also { it.start() }
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (code != REQ_SCAN) return
        if (results.isNotEmpty() && results.all { it == PackageManager.PERMISSION_GRANTED }) {
            render()
            Toast.makeText(this, s(R.string.nearby_scan), Toast.LENGTH_SHORT).show()
        } else Toast.makeText(this, s(R.string.permission_denied), Toast.LENGTH_LONG).show()
    }

    private fun resourcesScreen() {
        val p = prefs.policy
        content.addView(text(s(R.string.res_title), 17f, true))
        val enable = CheckBox(this).apply { text = s(R.string.share_enable); isChecked = p.sharingEnabled }
        fun num(label: Int, v: Int): EditText {
            content.addView(text(s(label), 14f))
            return EditText(this).apply { inputType = InputType.TYPE_CLASS_NUMBER; setText(v.toString()) }.also { content.addView(it) }
        }
        content.addView(enable)
        val battery = num(R.string.res_min_battery, p.minBatteryPercent)
        val data = num(R.string.res_max_data, p.maxMobileDataMbPerDay)
        val storage = num(R.string.res_max_storage, p.maxStorageMb)
        val relay = CheckBox(this).apply { text = s(R.string.res_allow_relay); isChecked = p.allowRelay }.also { content.addView(it) }
        val cache = CheckBox(this).apply { text = s(R.string.res_allow_cache); isChecked = p.allowCache }.also { content.addView(it) }
        val metered = CheckBox(this).apply { text = s(R.string.res_allow_metered); isChecked = p.allowMetered }.also { content.addView(it) }
        val decision = text("", 14f, true)
        fun save() {
            prefs.policy = p.copy(sharingEnabled = enable.isChecked,
                minBatteryPercent = battery.text.toString().toIntOrNull() ?: p.minBatteryPercent,
                maxMobileDataMbPerDay = data.text.toString().toIntOrNull() ?: p.maxMobileDataMbPerDay,
                maxStorageMb = storage.text.toString().toIntOrNull() ?: p.maxStorageMb,
                allowRelay = relay.isChecked, allowCache = cache.isChecked, allowMetered = metered.isChecked)
            decision.text = s(R.string.res_current) + ": " + decisionText(PolicyEngine.evaluate(prefs.policy, DeviceProbe.state(this), Role.HOST))
        }
        content.addView(Button(this).apply { text = s(R.string.res_saved).let { _ -> "✓" }; setOnClickListener { save(); Toast.makeText(this@MainActivity, s(R.string.res_saved), Toast.LENGTH_SHORT).show() } })
        content.addView(decision)
        save()
    }

    private fun settings() {
        content.addView(text(s(R.string.settings_language), 17f, true))
        val group = RadioGroup(this)
        listOf("" to s(R.string.settings_lang_system), "ar" to "العربية", "en" to "English").forEach { (code, label) ->
            group.addView(RadioButton(this).apply {
                text = label; id = View.generateViewId(); isChecked = prefs.language == code
                setOnClickListener { if (prefs.language != code) { prefs.language = code; recreate() } }
            })
        }
        content.addView(group)
        content.addView(text(s(R.string.settings_about), 17f, true))
        content.addView(text(s(R.string.settings_about_body) + "\nAndroid " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")", 14f))
    }

    companion object { private const val REQ_SCAN = 7 }
}
