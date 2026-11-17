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
| T8 | Chunked download with resume + multi-source scheduler | TODO |
| T9 | `wavenode` CLI + end-to-end two-process test | TODO |
| T10 | Android app skeleton | BLOCKED (no SDK) |
