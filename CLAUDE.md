# CLAUDE.md — working rules for WAVE-NET ∞

Read before every session: `MASTER_PLAN.md`, `TASKS.md`, `DECISIONS_LOG.md`, `TEST_REPORT.md`.

## Rules
- Work one task at a time from `TASKS.md`. A task is DONE only when its code builds,
  its tests run and pass, and `TEST_REPORT.md` records the actual command + output summary.
- Never claim radio (BLE / Wi-Fi Direct / Aware) behaviour from loopback/TCP tests.
- No home-made cryptography: only OpenSSL 3 primitives (Ed25519, SHA-256, CSPRNG).
- No secrets in the repo. Node keys live in the node data dir (default `~/.wavenet`), mode 0600.
- Do not invent benchmark numbers. Only measured results go in `TEST_REPORT.md`.
- Separate implemented vs planned features in every document.

## Build & test
```
cmake -S . -B build -G Ninja -DCMAKE_BUILD_TYPE=Debug
cmake --build build
ctest --test-dir build --output-on-failure
```
