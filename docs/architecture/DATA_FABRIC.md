# Data Fabric (implemented subset — T4)

## Implemented
- **Content ID** = SHA-256 of the full content (verifiable with `sha256sum`).
- **Manifest**: content ID, size, chunk size (16 KiB–1 MiB, default 256 KiB), SHA-256 per chunk,
  metadata (title, description, category, MIME, license, keywords, visibility, redistribution flag),
  file name (path separators rejected), publisher Ed25519 key, creation time, signature.
  Signed with domain string `WAVE manifest v1`. Decoder validates structure + signature.
- **ContentStore** (`<data>/store`): `blobs/<cid>` complete content; `partial/<cid>.part` resumable downloads.
  Each received chunk is hash-checked before writing (and fsynced). On restart, `open_partial` re-hashes
  existing chunks, so only bad/missing ones are fetched again. `finalize` re-checks the whole-file SHA-256.

## Planned (not implemented)
Chunk-level deduplication across files, Merkle DAG/tree root (for streaming proofs without full chunk list),
replication policies, quotas, expiration, version chains.
