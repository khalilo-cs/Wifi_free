# WAVE Turbo — transfer engine (T8: first real subset)

## Implemented
- **Chunked transfer** over authenticated sessions (`CHUNK_REQUEST` → `DATA_CHUNK`).
- **Multi-source download**: one worker per connected peer pulling from a shared chunk queue
  (faster peers naturally take more chunks). Per-peer delivered-chunk counts reported.
- **Integrity**: manifest content ID must equal requested ID; each chunk SHA-256-checked before write;
  whole-file SHA-256 checked at finalize.
- **Hostile peers**: a peer delivering one bad chunk is dropped for that download; chunk is requeued.
- **Resume**: interrupted downloads (caller stop, process restart, source disappearing) keep verified chunks;
  restart re-verifies and fetches only missing chunks — from any available source.
- **Serving rules**: only public items held complete locally; downloaded items re-served only if the publisher
  allowed redistribution; max 32 concurrent connections, 60 s idle timeout.

- **Pipelining** (T12): up to `window` (default 4) chunk requests in flight per source; server answers in order.
  Stop/interrupt lets in-flight chunks complete (≤ window−1 extra, all verified and kept).
- **Parallel connect** to all sources; TTFB (first verified chunk) reported.
- Benchmark tool with emulated latency: `docs/BENCHMARK_PLAN.md`.

## Not yet implemented
Bandwidth/latency estimation,
adaptive chunk sizing, congestion control, priorities, previews, compression, battery/data limits.
Only emulated-latency, single-host numbers exist (see BENCHMARK_PLAN.md); no radio numbers.
