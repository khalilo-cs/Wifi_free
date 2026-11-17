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

## T4 — manifest + content store (2026-11-17)
`./build/wavenet_tests` → 16 tests, 0 failed checks (also 16/16 under ASan+UBSan).
Checks: content_id == sha256_file; tampered manifest rejected; unsafe file name rejected; chunk-count mismatch rejected;
corrupted chunk refused; after simulated restart exactly the first half of chunks are recognised as verified;
finalize fails while incomplete and succeeds after completion; final SHA-256 equals source; empty file supported.

## T5 — SQLite catalog + local search (2026-11-17)
`./build/wavenet_tests` → 17 tests, 0 failed checks (17/17 under ASan+UBSan).
Checks: multi-term AND search, case-insensitive ASCII, category filter, pagination, private items hidden from
peer queries, remote-only entries not re-advertised, `%` escaped, SQL-injection string harmless, state never downgrades,
delete, persistence across reopen.

## T6 — TCP transport + authenticated session (2026-11-17)
`./build/wavenet_tests` → 21 tests, 0 failed checks; ASan+UBSan build → 21/21.
Over real loopback TCP sockets: mutual handshake, 1 MiB payload frame, allowlist rejection reported on both sides,
blocklisted server refused by client, network-ID mismatch refused, byte-identical replayed frame rejected.
(Loopback TCP only — says nothing about radio links.)

## T7 — UDP discovery (2026-11-17)
`./build/wavenet_tests` → 25 tests, 0 failed checks; ASan+UBSan → 25/25.
Rules tested: replay, stale, other network, too-old timestamp, self, forged signature, port 0 all rejected; newer accepted
and address updated; peer table capped at configured size. Real UDP sockets on loopback: unicast seed delivery verified;
**multicast group delivery on loopback verified in this container** (the test prints a SKIP/NOTE instead of failing where
multicast is unavailable). Not tested: real Wi-Fi LAN multicast between physical devices.
