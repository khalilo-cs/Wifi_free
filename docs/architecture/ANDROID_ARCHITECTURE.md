# Android Architecture (T16 — first stage)

## Status
| Item | State |
|---|---|
| Signed debug APK builds reproducibly in this environment | **Done** (verified with aapt2, apksigner v2/v3, zipalign, dexdump) |
| Pure-Kotlin logic (capabilities, permissions per Android version, resource policy) | **Done**, 14 JVM tests |
| UI: welcome, dashboard, nearby scan, resource limits, language (AR/EN, RTL), dark/light | Written, compiles |
| Run on a real phone / emulator | **Not done** — no device, emulator images need dl.google.com (blocked) |
| WAVE transfers on Android (LAN/TLS client, identity in Android Keystore) | **Not done** — next task |
| BLE / Wi-Fi Direct *transfer* | Not done (scanning only lists devices) |

## Toolchain (why no Android Gradle Plugin)
`dl.google.com` and `maven.google.com` (which redirects there) are blocked here and the request to allow them was
declined, so AGP, the official SDK, NDK, AndroidX and r8/d8 are unavailable. The build uses only reachable sources:
Debian `android-sdk-platform-23` (AOSP-built android.jar), Debian `aapt2`, `zipalign`, `apksigner`, `dalvik-exchange` (dx),
and Kotlin 2.0.21 from Maven Central. Consequences:
- Kotlin emits Java 8 bytecode without invokedynamic (`-Xlambdas=class` etc.) so dx can convert it; minSdk 26.
- Compile-time API is 23: newer APIs are used via string constants / version checks; `neverForLocation` cannot be declared
  (D18) so location permission is also requested on Android 12+.
- No code shrinking (APK ≈ 0.78 MB, mostly Kotlin stdlib). No NDK → the C++ core is not linked (no JNI yet).
When a modern SDK is available, switch to AGP and restore `neverForLocation`.

## Structure
- `net.wavenet.core` — pure Kotlin, no Android imports, unit-tested on the JVM:
  `RadioKind/CapabilityDetector` (hardware abstraction: unsupported media are reported, never assumed),
  `Permissions.forDiscovery(kind, sdk)`, `ResourcePolicy/PolicyEngine` (sharing OFF by default; battery, metered data,
  daily data cap, storage cap, per-role switches).
- `net.wavenet.app` — `MainActivity` (UI in code), `DeviceProbe` (features, radio on/off, battery, metered),
  `NearbyScanner` (user-started 12 s BLE + Wi-Fi Direct discovery, stopped in `onPause`), `Prefs`.

## Android rules respected
Runtime permissions requested only when the user taps "Scan"; radios optional (`required="false"`, location hardware too);
no background work, no foreground service yet; `allowBackup=false`.

## Build
```
cd android && gradle :app:test :app:apk     # → app/build/outputs/wavenet-0.1.0-debug.apk
```
Requires: `apt install android-sdk-platform-23 android-sdk-build-tools apksigner dalvik-exchange zip`, JDK 17+, Gradle 8.
The debug key is generated in `app/build/` and never committed.
