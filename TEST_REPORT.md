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

## T8 — node server, multi-source download, resume (2026-11-18)
Multi-node tests in one process over real loopback TCP (each node: own identity, SQLite catalog, store dir):
- publish → remote search (private item hidden) → download → SHA-256 equal to source; re-download fetches 0 chunks.
- interrupt after 10 chunks → new Catalog/Store objects (restart) → resume: already_had = 10, fetched = total − 10, hash OK.
- multi-source A + replica C: both delivered chunks, sum = chunk count.
- source A stopped mid-download → incomplete, then resume from {A(down), C} completes, hash OK.
- malicious authenticated peer corrupting chunks + honest peer → completes, rejected ≥ 1, hash OK.
- no-redistribution content not re-served by replica; allowlist node refuses unknown peer ("not trusted"), accepts after allow.
Results: GCC 30/30; ASan+UBSan 30/30; **TSan initially reported a data race in `TcpLink::close()`** (fd written during
concurrent recv) → fixed (atomic flag + shutdown; fd closed in destructor) → TSan clean in 3 consecutive runs, 30/30.

## T9 — `wavenode` CLI + end-to-end (2026-11-18)
`ctest --test-dir build --output-on-failure` → 2/2 passed (unit 30/30; e2e_two_node).
e2e (separate OS processes, loopback TCP + UDP multicast on 127.0.0.1): node key 0600; content id == `sha256sum`;
local search; discovery of A by E; remote search; fetch stopped after 7 chunks (rc=3); rerun resumed with
`already_had=7 fetched=26`; output byte-identical (`cmp`) and SHA-256 equal; allowlist node refused unknown node
("not trusted") then served it after `trust allow`.
Observed timings in one run (single host loopback, includes connect+handshake; **not a benchmark, not radio**):
8 MiB file, 33 chunks — first 7 chunks 0.044 s; remaining 26 chunks (6.3 MiB) 0.115 s.

## Secrets check
`git ls-files | xargs grep -lE "BEGIN (PRIVATE|EC|RSA)"` → no matches; keys are created only in node data dirs outside the repo.

## T10 — WAVE-5 scenario (2026-11-21)
`ctest --test-dir build --output-on-failure` → 3/3 passed (unit, e2e_two_node, e2e_wave5). `tests/e2e/wave5.sh` also passed 3/3 consecutive runs.
5 separate processes on one host (loopback TCP + UDP multicast on 127.0.0.1), 16 MiB file, 65 chunks:
- E discovered A, B, C, D; remote search returned the item from 3 sources (A, C, D).
- C (cache) and D (backup) replicated from A; hashes verified.
- E stopped after ≥20 chunks (22 in the recorded run: chunks already in flight from 3 sources still complete), then A was killed.
- E resumed from C + D only: already_had=22, fetched=43 (21 from D, 22 from C), SHA-256 == source.
- D in allowlist mode refused an unknown node ("not trusted").
- No external Internet used (all data local).
Measured in one run (single host, loopback, **not radio, not a benchmark**): replication 16 MiB in ~0.30 s; client peak RSS
≈ 11–13 MiB, CPU ≈ 0.06–0.13 s per fetch (getrusage).
Not covered: B as a relay (multi-hop not implemented, T15), real radio, energy consumption, service (code) failover.
**Test-design bug found:** the first version assumed exactly 20 chunks before stopping; with several sources a few more complete.
Behaviour is correct (extra chunks are verified and kept); the test now checks resume == chunks actually fetched.

## T11 — TLS 1.3 + channel binding (2026-11-21)
- GCC: 33 tests, 0 failed checks; ASan+UBSan: 33/33; TSan: 33/33 twice, no warnings. `ctest`: 3/3 (unit, two-node, WAVE-5).
- `tls_session_encrypted_and_bound`: medium `tcp+tls1.3`, 32-byte binding equal on both ends, 1 MiB frame delivered.
- `tls_passive_eavesdropper_sees_no_plaintext`: byte-level tap between client and server. **Control without TLS: marker
  found in captured bytes. With TLS: marker not found** (traffic was captured, non-empty).
- `tls_active_mitm_detected_by_channel_binding`: attacker terminates TLS on both sides and relays frames → server
  "bad HELLO: bad signature", client handshake fails.
- **Mutation check:** with `channel_binding()` forced empty, the same test FAILS ("server: accepted | client: accepted"),
  proving the binding is what defeats the attack. Code restored afterwards and test passes again.
- WAVE-5 rerun: all download sources reported as `tls+tcp:…`.
- Bugs found during T11: (1) process killed by SIGPIPE (exit 141) when a TLS peer closed → D13; (2) malicious-peer test
  silently stopped testing chunk rejection because the fake peer spoke plain TCP → fake peer now uses TLS.
