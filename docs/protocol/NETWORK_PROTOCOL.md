# WAVE-NP v1 — WAVE Network Protocol (experimental)

**Status:** an experimental protocol for this project only, *not* an IETF/Internet standard.
Implemented in `core/src/protocol.cpp` (T3). Everything is little-endian.

## Frame layout
| Offset | Size | Field |
|---|---|---|
| 0 | 4 | magic `WVNP` (0x504E5657 LE) |
| 4 | 1 | version (=1) |
| 5 | 2 | message type |
| 7 | 1 | flags (reserved, 0) |
| 8 | 2 | header length (≥107; extra bytes = forward-compatible extensions, skipped but signed) |
| 10 | 4 | network ID (separates independent WAVE networks) |
| 14 | 32 | source Ed25519 public key |
| 46 | 32 | destination NodeID (all zero = link peer / broadcast) |
| 78 | 8 | request ID (request/response correlation) |
| 86 | 8 | sequence number (strictly increasing per session) |
| 94 | 8 | timestamp (ms since Unix epoch) |
| 102 | 1 | hop limit |
| 103 | 4 | payload length (≤ 1 MiB + 64 KiB) |
| hl | n | payload |
| hl+n | 64 | Ed25519 signature |

Signature input: `str("WAVE-NP/1 frame") || blob(context) || header || payload`.
`context` is not transmitted: for TCP sessions it is the *receiver's* handshake nonce, which
binds every frame to one session (cross-session replay fails). For UDP announcements it is empty;
those rely on timestamp windows (T7).

## Validation (decoder)
Rejects: bad magic, unknown version, header length out of range, payload > limit, total size mismatch,
trailing bytes, bad signature. Every single-bit flip of a valid frame is rejected (unit test).

## Versioning
Version byte = major. Unknown major → reject. Minor additions → new header extension bytes
(header length grows) or new message types; old decoders skip extensions and answer unknown
types with `ERROR`.

## Message types
Implemented use: HELLO, HELLO_ACK, ERROR, NODE_ANNOUNCE, SEARCH_*, MANIFEST_*, CHUNK_REQUEST, DATA_CHUNK.
Reserved (defined, not yet used): NODE_CAPABILITY, LINK_REPORT, SERVICE_ADVERTISE, CONTENT_ADVERTISE,
INTENT_REQUEST, ROUTE_PROPOSAL, ROUTE_COMMIT, CHUNK_RECEIPT, STATE_CHECKPOINT, SERVICE_RECOVER,
GATEWAY_ADVERTISE, TRUST_REVOKE.

## Not provided in v1 (known gaps)
- Confidentiality comes from the transport, not from WAVE-NP: TCP sessions run inside TLS 1.3 (T11) and the
  signature context includes the TLS exporter value (channel binding, see `docs/security/SESSION_SECURITY.md`).
  UDP `NODE_ANNOUNCE` frames remain signed-only (public by design).
- No multi-hop routing yet (hop limit carried but not used).

## Prior standards considered
TLS 1.3 / QUIC (transport security — planned), DTN Bundle Protocol v7 RFC 9171 (store-carry-forward
— to study), IPv6 over BLE RFC 7668 (radio link — to study).
