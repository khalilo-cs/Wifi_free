# WAVE-NET ∞ — experimental distributed content network

**Status: v0.1 vertical slice (Linux, TCP/UDP only).** Devices voluntarily publish, discover, search and download
content from each other, with signed identities and end-to-end integrity verification.
Radio transports (BLE, Wi-Fi Direct/Aware) and the Android app are **not implemented yet**.
Reaching the global Internet always requires a real gateway; nothing here replaces one.

## What works today (tested — see TEST_REPORT.md)
- Ed25519 node identity (`NodeID = SHA-256(pubkey)`), key file 0600.
- WAVE-NP v1 signed frames (experimental project protocol), fuzzed.
- TLS 1.3 encrypted, mutually authenticated sessions (channel-bound), replay protection, allowlist/blocklist trust.
- LAN discovery via signed UDP multicast announces (+ unicast seeds).
- Publishing with metadata, signed manifests, SQLite catalog, local + remote search (private items never exposed).
- Chunked, multi-source, pipelined, resumable downloads; per-chunk and whole-file SHA-256 verification; corrupt peers dropped.
- Opt-in relay nodes (`serve --relay`) and multi-hop routes (`fetch --route host:port,NODEID,...`), end-to-end encrypted.
- Per-address rate limiting and connection caps; threat model in `docs/security/THREAT_MODEL.md`.

## Build & test (Ubuntu 24.04: g++ ≥ 13, cmake, ninja, libssl-dev, libsqlite3-dev)
```bash
cmake -S . -B build -G Ninja -DCMAKE_BUILD_TYPE=Debug
cmake --build build
ctest --test-dir build --output-on-failure      # unit tests + 2-process end-to-end test
```
Sanitizers: `-DWAVENET_SANITIZE=ON` (ASan+UBSan). Fuzzing (clang + libclang-rt-18-dev):
`CC=clang CXX=clang++ cmake -S . -B build-fuzz -DWAVENET_FUZZ=ON -DWAVENET_SANITIZE=ON -DCMAKE_CXX_FLAGS=-fsanitize=fuzzer-no-link && cmake --build build-fuzz && build-fuzz/tests/fuzz/fuzz_frame -max_total_time=60`

## Try it (two terminals, same LAN or same machine)
```bash
W=build/linux-node/wavenode
# terminal 1 — publisher
$W --data /tmp/nodeA publish ./book.pdf --title "Physics Book" --category books --keywords "science"
$W --data /tmp/nodeA serve --name node-a          # add --multicast-if 127.0.0.1 for single-machine tests
# terminal 2 — downloader
$W --data /tmp/nodeE peers
$W --data /tmp/nodeE search-net physics
$W --data /tmp/nodeE fetch <CONTENT_ID> --out ./book.pdf   # Ctrl-C / rerun resumes
```
Ports: TCP 47801 (content), UDP 47800 multicast 239.255.77.86 (discovery). Firewalls must allow them.

## Security notes
Node-to-node connections use **TLS 1.3 (OpenSSL)** with channel binding: the WAVE-NP handshake signs the TLS exporter
value, so peers are authenticated by their Ed25519 node keys and a man-in-the-middle is detected. No certificate
authority is used. UDP discovery announcements are signed but **not encrypted** (they reveal node name, port and item count).
See `docs/security/` and `docs/protocol/NETWORK_PROTOCOL.md`.

## Project tracking
`MASTER_PLAN.md` · `TASKS.md` · `DECISIONS_LOG.md` · `TEST_REPORT.md` · `CLAUDE.md` · `docs/`

## Android (stage 1, experimental)
`cd android && gradle :app:test :app:apk` builds a signed debug APK (see `docs/architecture/ANDROID_ARCHITECTURE.md` for the
toolchain without Google's SDK servers). It detects radios, asks permissions, sets resource limits, and lists nearby
BLE / Wi-Fi Direct devices. It does **not** transfer content yet and has **not** been run on a real device.
