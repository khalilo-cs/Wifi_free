# Benchmark Plan & Results (T12)

## Tool
`build-release/bench_transfer [MiB] [out.md]` (Release build). One process, two serving nodes (A publisher, B replica),
fresh client per scenario, TLS 1.3 sessions. **Latency is emulated** by an in-process TCP relay that delays every
forwarded block by a fixed one-way delay (RTT ≈ 2×). No bandwidth cap, no packet loss.

**These numbers describe the protocol engine on one machine. They are NOT Bluetooth, Wi-Fi Direct or Wi-Fi LAN
measurements** — those require real devices (not available in this environment).

## Metrics
connect+handshake (TCP + TLS 1.3 + WAVE-NP mutual auth) · TTFB (start → first verified chunk stored, includes connecting
and manifest fetch) · total time · throughput (bytes fetched / total time) · resume time (complete the second half after a
stop at 50%).

## Results — 2026-11-21, 32 MiB, 128 × 256 KiB chunks
See `benchmarks/results/2026-11-21-loopback-emulated.md` (second run, after parallel connect). Key observations:
- Window 1 is RTT-bound: 25 ms one-way delay → 4.4 MiB/s; window 16 → 46.3 MiB/s (single source).
- Two sources roughly double throughput at small windows (25 ms, window 1: 4.4 → 8.5 MiB/s).
- Connect+handshake ≈ 4 × one-way delay (≈ 2 RTT: TLS 1.3 + WAVE HELLO/ACK).
- First run exposed sequential connecting (TTFB 347 ms with 2 sources vs 254 ms with 1 at 25 ms) → fixed with parallel
  connect (249 ms). 0 ms rows vary ±25 % between runs (CPU scheduling noise on loopback).

## Not yet measured
Real radio media (BLE, Classic, Wi-Fi Direct/Aware, LAN), packet loss, bandwidth-limited links, CPU/battery on phones,
Time-to-Discover on real networks. Hybrid-transport comparisons depend on radio Links (not implemented).
