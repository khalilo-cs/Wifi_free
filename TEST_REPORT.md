# Test Report (only results actually executed)

## T0
No code yet. Environment: Linux, g++ 13.3, clang 18.1, CMake 3.28, Ninja, OpenSSL 3.0.13, SQLite3, Python 3.13. No Android SDK.

## T1 — bytes/hex/SHA-256 (2026-11-17)
`cmake --build build && ./build/wavenet_tests` → 4 tests, 0 failed checks.
SHA-256 checked against FIPS 180-2 vectors ("" and "abc"); streamed file hash == in-memory hash (300 kB);
codec rejects truncated input and over-limit blob lengths.
