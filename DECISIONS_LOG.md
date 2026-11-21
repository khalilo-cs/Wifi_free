# Decisions Log

| # | Date | Decision | Reason |
|---|---|---|---|
| D1 | 2026-11-17 | Core in C++20 + CMake/Ninja, Linux first | Toolchain available; directive preference. |
| D2 | 2026-11-17 | OpenSSL 3 for Ed25519 / SHA-256 / RNG | Audited library already installed; no home-made crypto. libsodium not installed. |
| D3 | 2026-11-17 | SQLite for the local content catalog | Installed, embedded, portable to Android. |
| D4 | 2026-11-17 | First transport = TCP, discovery = UDP; radio later behind `Transport` interface | No radio hardware in a cloud container. Loopback tests do NOT prove radio behaviour. |
| D5 | 2026-11-17 | Android app deferred | No Android SDK/emulator in environment. |
| D6 | 2026-11-17 | Own minimal test harness (no gtest) | gtest not installed; avoid network dependencies for builds. |
| D7 | 2026-11-17 | WAVE-NP v1 frames are signed, not encrypted (*superseded for TCP by D12*) | Avoid custom AEAD design; confidentiality to come from TLS 1.3/QUIC or Noise (see NETWORK_PROTOCOL.md). |
| D8 | 2026-11-17 | Session binding via signed (untransmitted) receiver nonce | Prevents replay of frames across sessions using only Ed25519. |
| D9 | 2026-11-17 | Catalog search uses escaped LIKE, not FTS5 | FTS5 not guaranteed in Android's system SQLite; catalogs are small in v0. Revisit with bundled SQLite. |
| D10 | 2026-11-18 | Drop a peer after one bad chunk (per download) | Hash mismatch is unambiguous evidence of a faulty/malicious peer; cheap defence against pollution. |
| D11 | 2026-11-18 | `Link::close()` = shutdown only; fd freed in destructor | Fixes TSan-detected race; avoids fd reuse by concurrent reader. |
| D12 | 2026-11-21 | TLS 1.3 without PKI + exporter channel binding in WAVE-NP signatures | Standard primitives only; node keys stay the single identity; MITM detected (tested). |
| D13 | 2026-11-21 | TLS layer sets SIGPIPE to ignore if still default | OpenSSL uses write(); a closing peer killed the process (exit 141) in tests. |
| D14 | 2026-11-21 | Per-IP token bucket checked before TLS; per-IP + total connection caps; per-session request bucket | Cheapest possible rejection point for floods; all maps bounded. Defaults: burst 20, 5/s, 8 conns/IP, 500 req/s. |
| D15 | 2026-11-21 | Pipelining window default 4 (CLI `--window`, max 64); parallel connect to sources | Measured: window removes RTT-bound behaviour; sequential connect inflated TTFB. 4 keeps memory low (≤ 4 × 1 MiB per source). |
| D16 | 2026-11-21 | Relays splice raw bytes; TLS + WAVE handshake run end-to-end through them; relaying opt-in | Relays never hold keys or plaintext; reuses tested session security; directive: no forced resource sharing. |
| D17 | 2026-11-21 | Route requests detected by peeking magic+version+type (7 bytes), not 1 byte | Mutation testing showed 1-byte detection confused plaintext sessions with relay requests. |
| D18 | 2026-11-21 | Request location on all Android versions for BLE/Wi-Fi Direct scans (temporary) | `neverForLocation` needs an API 31+ android.jar, unavailable here; without it Android requires location. Revert with a modern SDK. |
| D19 | 2026-11-21 | Build Android without AGP (Debian android.jar 23 + aapt2 + dx + apksigner, Kotlin from Maven Central), minSdk 26 | Google hosts are blocked and access was declined; this is the only reproducible path available. |
| D20 | 2026-11-21 | Bouncy Castle 1.80 (bcprov/bctls/bcpkix) for Ed25519 and TLS 1.3 + exporter on Android | Android lacks Ed25519 before API 33 and has no public TLS exporter API; BC is a widely used, audited library. Verified: no invokedynamic in its 6,614 classes. |
| D21 | 2026-11-21 | Build-time guard against Android-unsafe invokedynamic (app: none; libs: reviewed set only; no calls to unsafe stdlib methods/packages) | dx accepts invokedynamic but Android cannot run LambdaMetafactory. kotlin-stdlib contains it in 5 Comparisons methods, Uuid <clinit>, jdk8 streams. Guard mutation-tested. |
| D22 | 2026-11-21 | Phone node runs only while the app is in the foreground; transfers resume later | Directive: no hidden background work; avoids foreground-service APIs not present in the API 23 build jar. |
| D23 | 2026-11-21 | Identity seed encrypted with an AES-256-GCM key inside Android Keystore | Keystore Ed25519 needs API 33; wrapping keeps the private key off disk in clear on API 26+. |
