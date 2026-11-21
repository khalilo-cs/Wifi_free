# Session Authentication (T6) and Encryption (T11)

## Encryption (T11)
- Every node connection runs over **TLS 1.3 only** (OpenSSL 3, no compression, no session tickets).
- TLS certificates are ephemeral self-signed Ed25519 certs and are **not verified** — there is no PKI.
  Instead, both sides derive `binding = TLS-Exporter("EXPORTER-WAVE-NP-v1", 32 bytes)` (RFC 8446 §7.5; the
  approach of RFC 9266 `tls-exporter` channel binding) and append it to every WAVE-NP signature context.
- Effect: a MITM terminating TLS separately with each side has two different exporter values, so the relayed
  HELLO fails verification ("bad signature"). Tested, including a mutation test showing the attack succeeds
  if binding is removed.

## Handshake

Contexts below are additionally suffixed with the channel-binding value (empty on plain TCP links used in tests).

1. Initiator → `HELLO{nonce_i}` (signed, context = binding).
2. Responder checks network ID, ±5 min clock skew, trust policy (allowlist/blocklist), not-self;
   replies `HELLO_ACK{nonce_r}` signed with context `nonce_i` (fresh proof of key possession), or `ERROR`.
3. Initiator verifies ACK with `nonce_i`, applies its own trust policy.
4. Every later frame is signed with context = **receiver's** nonce; receiver requires src == handshake key,
   strictly increasing `seq`, same network ID. Any violation closes the link.

Properties (tested): mutual authentication of Ed25519 keys; untrusted/blocked nodes refused; replay within a
session rejected; replay across sessions rejected (frame-level context test).

Limitations: traffic metadata (who talks to whom, sizes, timing) is visible to observers; UDP announces are not
encrypted; no handshake rate limiting yet (T13); a node's long-term Ed25519 key is unencrypted at rest.
Forward secrecy: provided by TLS 1.3 ephemeral (EC)DHE key exchange.
