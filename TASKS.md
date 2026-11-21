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
| T10 | WAVE-5 scenario script: 5 processes (publisher, relay*, cache, backup host, client) over loopback | DONE (B relay pending T15) |
| T11 | Transport encryption (TLS 1.3 via OpenSSL, or Noise XX) — removes "not encrypted" limitation | TODO |
| T12 | Request pipelining + measured benchmark suite (TTFB, throughput, resume time) | TODO |
| T13 | Threat model document (THREAT_MODEL.md) + rate limiting on handshakes | TODO |
| T14 | Prior-art research docs (PRIOR_ART.md, REFERENCES.md, NOVELTY_HYPOTHESES.md, RESEARCH_GAPS.md) | TODO |
| T15 | Multi-hop relay (ROUTE_PROPOSAL/COMMIT) — *relay node currently NOT implemented* | TODO |
| T16 | Android app (Kotlin) + JNI binding of core; BLE/Wi-Fi Direct `Link`s | BLOCKED (no Android SDK in this environment) |
