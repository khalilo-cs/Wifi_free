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

## Not yet implemented
Request pipelining (currently 1 outstanding request per peer → RTT-bound), bandwidth/latency estimation,
adaptive chunk sizing, congestion control, priorities, previews, compression, battery/data limits.
No throughput numbers are claimed yet (benchmark suite pending).
