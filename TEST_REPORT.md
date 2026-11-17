# Test Report (only results actually executed)

## T0
No code yet. Environment: Linux, g++ 13.3, clang 18.1, CMake 3.28, Ninja, OpenSSL 3.0.13, SQLite3, Python 3.13. No Android SDK.

## T1 — bytes/hex/SHA-256 (2026-11-17)
`cmake --build build && ./build/wavenet_tests` → 4 tests, 0 failed checks.
SHA-256 checked against FIPS 180-2 vectors ("" and "abc"); streamed file hash == in-memory hash (300 kB);
codec rejects truncated input and over-limit blob lengths.

## T2 — node identity (2026-11-17)
`./build/wavenet_tests` → 8 tests, 0 failed checks. Includes RFC 8032 §7.1 TEST 1 verification vector,
signature/message tamper rejection, wrong-key rejection, 0600 persistence, refusal of world-readable key and garbage key.

## T3 — WAVE-NP framing (2026-11-17)
- `./build/wavenet_tests` (GCC) → 12 tests, 0 failed checks: roundtrip, rejection of all single-bit flips
  of a valid frame, session-context binding, size limits, src/signer mismatch.
- Same tests under clang ASan+UBSan (`build-fuzz/wavenet_tests`) → 12 tests, 0 failed checks.
- libFuzzer `fuzz_frame`, 60 s, max_len 4096: 1,155,856 executions, no crash/leak/UB reported.
  Limitation: random inputs cannot produce valid signatures, so post-signature handling is covered by unit tests, not the fuzzer.
- Environment note: installed `libclang-rt-18-dev` (apt) to get libFuzzer/sanitizer runtimes.
