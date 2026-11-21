# Routing / Multi-hop Relay (T15)

## Implemented: source-routed relay circuits
1. Client connects (TCP) to relay R1 and sends a plaintext, Ed25519-signed `ROUTE_PROPOSAL{target NodeID, nonce}`.
2. R1 checks: relaying enabled by its owner (`--relay`, off by default), network ID, ±60 s timestamp, trust policy,
   target ≠ itself, `max_relays` (16), and resolves the target from its **discovery table** (directly reachable peers).
3. R1 opens TCP to the target, answers `ROUTE_COMMIT` signed over the client's nonce, then **splices raw bytes**
   (idle timeout 60 s). Refusals are signed `ERROR` frames with a reason.
4. More hops: the client sends the next `ROUTE_PROPOSAL` *through* the circuit to the next relay (E→C→B→A tested).
5. Finally the client runs **TLS 1.3 + WAVE-NP handshake end-to-end** with the target through all relays and requires
   the authenticated peer ID == requested target (a relay pointing to another node is detected).

The server distinguishes a relay request from a session by peeking the first 7 bytes
(WAVE-NP magic + version + type == ROUTE_PROPOSAL); TLS starts with 0x16, plaintext sessions with HELLO.

## What relays can and cannot do
- Cannot read or modify content, or impersonate the target (end-to-end TLS + channel binding + target pinning; tested).
- Can see metadata: who asks for which target, timing, volume. Can drop traffic (client then uses other sources).
- Per-address rate limits / connection caps apply to relay connections too.

## Not implemented
Automatic route discovery (client must name the hops; CLI `--route host:port,ID,...`), route metrics/selection,
`LINK_REPORT` exchange, loop detection beyond "target ≠ relay", onion-style metadata protection, store-carry-forward.
Tests run on one host: they prove the protocol logic, not radio multi-hop range or performance.
