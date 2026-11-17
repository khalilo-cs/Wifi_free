# Decisions Log

| # | Date | Decision | Reason |
|---|---|---|---|
| D1 | 2026-11-17 | Core in C++20 + CMake/Ninja, Linux first | Toolchain available; directive preference. |
| D2 | 2026-11-17 | OpenSSL 3 for Ed25519 / SHA-256 / RNG | Audited library already installed; no home-made crypto. libsodium not installed. |
| D3 | 2026-11-17 | SQLite for the local content catalog | Installed, embedded, portable to Android. |
| D4 | 2026-11-17 | First transport = TCP, discovery = UDP; radio later behind `Transport` interface | No radio hardware in a cloud container. Loopback tests do NOT prove radio behaviour. |
| D5 | 2026-11-17 | Android app deferred | No Android SDK/emulator in environment. |
| D6 | 2026-11-17 | Own minimal test harness (no gtest) | gtest not installed; avoid network dependencies for builds. |
