# Discovery (T7)

## Implemented
- `NODE_ANNOUNCE` WAVE-NP frame over UDP: payload `{tcp_port u16, roles u32, content_count u32, name ≤64}`.
- Transport: IPv4 multicast `239.255.77.86:47800` (admin-scoped, TTL 1, loop on) and/or unicast seed list.
- Acceptance: valid signature, same network ID, timestamp within ±60 s, **strictly newer** than last accepted
  announce of that node (replay/stale protection), not self, port ≠ 0, datagram ≤ 2 KiB.
- Peer table bounded (default 1024, stalest evicted); entries expire after 30 s without announces.
- Peer address = datagram source IP + advertised TCP port (an announcer cannot point peers at a third-party IP).

## Limitations
Discovery itself does not prove reachability; the TCP handshake (T6) authenticates the peer.
Sybil resistance: none beyond the bounded table (any key can announce). IPv6 multicast not implemented.
Android will need `WifiManager.MulticastLock`. BLE advertising / Wi-Fi Aware discovery are planned (no hardware here).
