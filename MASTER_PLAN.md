# WAVE-NET ∞ — Master Plan

WAVE-NET is an **experimental** peer-to-peer content and service network. Phones/PCs
voluntarily act as client, publisher, host, relay, cache or gateway. Local services work
without external Internet; reaching the global Internet always requires a real gateway.

## Strategy
Build a small, correct, tested core first (C++20, Linux), prove publish → discover →
download → verify → resume between real processes over TCP/UDP on a LAN/loopback,
then add radio transports (Android) behind the same `Transport` abstraction.

## Phases (status kept in TASKS.md)
| Phase | Scope | Status |
|---|---|---|
| 0 | Repository audit, tracking docs | done |
| 1 | Foundation: hashing, identity, WAVE-NP framing, storage, tests | done |
| 2 | First network: discovery, connections, announcements | done (LAN, single hop); multi-hop planned |
| 3 | Data transfer: chunks, resume, integrity, multi-source | done (v0); pipelining/benchmarks planned |
| 4 | WAVE Store: publisher, metadata, search | CLI only; UI/app-market planned |
| 5 | Android app (blocked: no Android SDK in current environment) | blocked |
| 6–10 | Services, security hardening, gateways, hardware, release | planned |

## First milestone: vertical slice (Part 20 of the directive)
Node identity · LAN discovery · WAVE-NP messages · publisher · metadata · SHA-256 ·
chunked transfer · resume · local + remote search · CLI interface (desktop/Linux).
