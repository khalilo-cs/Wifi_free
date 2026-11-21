# Tasks (one at a time; DONE = built + tested + recorded in TEST_REPORT.md)

| ID | Task | Status |
|---|---|---|
| T0 | Audit + tracking docs | DONE |
| T1 | CMake skeleton, test harness, SHA-256 + hex + byte codec | DONE |
| T2 | Node identity (Ed25519 keygen/save/load/sign/verify, NodeID) | DONE |
| T3 | WAVE-NP frame format: encode/decode/validate/sign + fuzz target | DONE |
| T4 | Content-addressed chunk store + signed manifest | DONE |
| T5 | SQLite catalog + local search | DONE |
| T6 | TCP transport + authenticated session (challenge/response, replay protection) | DONE |
| T7 | UDP LAN discovery (signed NODE_ANNOUNCE) | DONE |
| T8 | Chunked download with resume + multi-source scheduler | DONE |
| T9 | `wavenode` CLI + end-to-end two-process test | DONE |
| T10 | WAVE-5 scenario script: 5 processes (publisher, relay, cache, backup host, client) over loopback | DONE |
| T11 | Transport encryption: TLS 1.3 (OpenSSL) + channel binding of WAVE-NP signatures | DONE |
| T12 | Request pipelining + measured benchmark suite (TTFB, throughput, resume time) | DONE (emulated latency only) |
| T13 | Threat model document (THREAT_MODEL.md) + rate limiting on handshakes | DONE |
| T14 | Prior-art research docs (PRIOR_ART.md, REFERENCES.md, NOVELTY_HYPOTHESES.md, RESEARCH_GAPS.md) | TODO |
| T15 | Multi-hop relay (ROUTE_PROPOSAL/COMMIT), source-routed, end-to-end TLS | DONE (manual routes) |
| T16 | Android app stage 1: reproducible signed APK, capability detection, permissions, resource policy, AR/EN UI | DONE (built + JVM-tested; NOT run on a device) |
| T17 | Android stage 2: full WAVE-NP in Kotlin (TLS 1.3 + binding), publish/discover/search/download/serve, Keystore-wrapped identity; interop-tested vs C++ | DONE (JVM-verified; awaiting first run on real phones) |
| T18 | Bluetooth Classic RFCOMM + Wi-Fi Direct transfers, BLE beacon discovery, hybrid multi-medium downloads | DONE IN CODE (protocol over non-IP streams + hybrid tested on JVM; radio code NOT field-tested) |

## Remaining roadmap toward the full directive (each item = separate, testable task)
| ID | Task | Status |
|---|---|---|
| T14 | Prior-art research docs (PRIOR_ART, REFERENCES, NOVELTY_HYPOTHESES, RESEARCH_GAPS) | TODO |
| T19 | Field test on real phones (2–5 devices): LAN, hotspot, Wi-Fi Direct, Bluetooth; measured speeds/battery | NEEDS DEVICES |
| T20 | Background transfers via a visible foreground service (needs modern SDK for API 26+ notification channels) | TODO |
| T21 | WAVE Store app market: APK signature/permission display, update history, ratings | TODO |
| T22 | Media: thumbnails/previews, progressive playback once data is local | TODO |
| T23 | Community/global content index (signed, paginated, expiring) + reporting & takedown protocol | TODO |
| T24 | Automatic multi-hop routing (route discovery, metrics, loop protection) on top of T15 relays | TODO |
| T25 | Store-carry-forward (DTN, RFC 9171-inspired) for disconnected communities | TODO |
| T26 | Gateway nodes: share real Internet access with explicit owner consent and limits | TODO |
| T27 | Service runtime (W5): sandboxed WebAssembly / local web apps served over WAVE | TODO |
| T28 | Continuity (W7): service checkpoint + failover between hosts | TODO |
| T29 | Wi-Fi Aware transport (API 26+ devices that support it) | TODO |
| T30 | Hardware research (W9 / Phase 9): radio modules, embedded node, power tests | TODO |
| T31 | External security audit before public release; release signing key managed by the owner | TODO (owner) |
