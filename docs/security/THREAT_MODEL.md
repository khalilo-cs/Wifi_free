# Threat Model — WAVE-NET v0.1 (T13)

Scope: the code in this repository (Linux node, TCP+TLS transport, UDP discovery). Radio links, Android and
multi-hop routing do not exist yet and are out of scope until implemented.

## Assets
Node private key · user's private/unpublished files · integrity of downloaded content · node resources
(CPU, memory, disk, bandwidth, battery on phones) · privacy of what a user publishes/downloads.

## Attackers
A1 passive observer on the LAN · A2 active network attacker (MITM, injection, replay) · A3 malicious node with a
valid key (one or many) · A4 malicious publisher · A5 local user/process on the same machine.

## Threats and status
Status legend: **M** = mitigated and tested · **P** = partial · **O** = open (known gap).

| # | Threat | Attacker | Defence | Status | Evidence |
|---|---|---|---|---|---|
| 1 | Read traffic | A1 | TLS 1.3 on every session | M | `tls_passive_eavesdropper_sees_no_plaintext` (control shows tap works) |
| 2 | MITM / impersonate a node | A2 | Ed25519 handshake signing TLS exporter (channel binding) | M | `tls_active_mitm_detected_by_channel_binding` + mutation check |
| 3 | Replay frames in / across sessions | A2 | strictly increasing seq; signatures bound to receiver nonce | M | `session_rejects_replay`, `np_context_binding` |
| 4 | Forged / tampered frames | A2 | Ed25519 signature on every frame; strict decoder | M | `np_rejects_every_single_bit_flip`, fuzzing (1.15 M inputs) |
| 5 | Fake or corrupted content | A3 | per-chunk SHA-256 from signed manifest; whole-file SHA-256; peer dropped after a bad chunk | M | `node_rejects_corrupt_peer`, `store_partial_resume_and_integrity` |
| 6 | Manifest swapped for other content | A3 | requested content ID must equal manifest content ID; manifest signature | M | `PeerClient::manifest` check, `manifest_sign_encode_decode` |
| 7 | Path traversal via file name | A4 | file names with `/`, `\`, `..`, NUL rejected; files stored by hash only | M | `manifest_rejects_unsafe_names_and_counts` |
| 8 | Connection / handshake flood | A2/A3 | per-IP token bucket *before* TLS; per-IP and total connection caps | M | `server_refuses_connection_flood`, `server_per_ip_connection_cap_and_request_limit` |
| 9 | Request flood inside a session | A3 | per-session token bucket, session closed when exceeded | M | same test |
| 10 | Memory exhaustion via huge frames / tables | A2/A3 | frame ≤ ~1.06 MiB checked from header before allocation; bounded peer table and limiter maps | M | `np_limits`, `discovery_peer_table_bounded`, `ratelimit_bounded_keys` |
| 11 | Private content leaks to peers | A3 | private items never returned by search or served | M | `catalog_put_get_search`, `node_publish_search_download_verify` |
| 12 | Unauthorised redistribution | A3 | replicas don't re-serve when publisher forbids it | P | `node_redistribution_and_trust`. A modified client can still copy bytes it received — technical limit, not DRM. |
| 13 | Untrusted nodes | A3 | allowlist / blocklist trust policy | M | `session_allowlist_rejects_untrusted`, e2e tests |
| 14 | Sybil (many fake identities) | A3 | bounded tables, per-IP limits, optional allowlist | P | Identities are free to create; no proof-of-work / web-of-trust yet. |
| 15 | Eclipse (victim only sees attacker nodes) | A3 | multi-source download, integrity checks | O | No peer-diversity policy yet; discovery accepts any signed announce. |
| 16 | Discovery spoofing / replay | A2 | signed announce, ±60 s window, strictly newer timestamp | M | `discovery_validation_rules` |
| 17 | Discovery metadata exposure (name, port, item count) | A1 | none (announces are public by design) | O | Document to users; option to disable multicast (`--no-multicast`). |
| 18 | Traffic analysis (who talks to whom, sizes) | A1 | none | O | Out of scope for v0. |
| 19 | Routing manipulation / poisoning | A3 | n/a — no multi-hop routing yet | O | Must be designed together with T15. |
| 20 | Malicious applications (APK) | A4 | none yet: no APK scanning, signature verification or install flow | O | WAVE Store app market not implemented. Integrity ≠ safety. |
| 21 | Malicious publisher identity theft | A5 | key file 0600, refuses group/world-readable keys and symlinks | P | Key not encrypted at rest (needs OS keystore). |
| 22 | Spam / illegal content, takedown | A4 | local `unpublish`; blocklist | P | No reporting/takedown protocol across nodes yet. |
| 23 | Clock manipulation | A2 | ±5 min (sessions) / ±60 s (announces) skew windows | P | Large real clock errors cause refusals (fail-closed). |

## Out of scope / assumptions
- OpenSSL 3 and the OS are trusted. No hardware attacks.
- Content *legality* and *safety* are not judged by hashes: a verified file can still be malicious.
