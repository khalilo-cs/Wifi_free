# Session Authentication (T6)

1. Initiator → `HELLO{nonce_i}` (signed, empty context).
2. Responder checks network ID, ±5 min clock skew, trust policy (allowlist/blocklist), not-self;
   replies `HELLO_ACK{nonce_r}` signed with context `nonce_i` (fresh proof of key possession), or `ERROR`.
3. Initiator verifies ACK with `nonce_i`, applies its own trust policy.
4. Every later frame is signed with context = **receiver's** nonce; receiver requires src == handshake key,
   strictly increasing `seq`, same network ID. Any violation closes the link.

Properties (tested): mutual authentication of Ed25519 keys; untrusted/blocked nodes refused; replay within a
session rejected; replay across sessions rejected (frame-level context test).

Limitations: **no encryption** (eavesdroppers can read traffic); a replayed HELLO makes the responder send one
ACK (harmless, but costs a signature — rate limiting in node server); no forward secrecy. Planned: TLS 1.3 / Noise XX.
