# Radio Fabric / Transport (T6)

## Implemented
- `Link` interface: reliable ordered frame link (`send_frame`, `recv_frame(timeout)`, `medium()`).
- `TcpLink` / `TcpListener` / `tcp_connect` (IPv4/IPv6, timeouts, TCP_NODELAY, frame size checked
  from the header before allocating — max ≈ 1.06 MiB).

- `tls_wrap(link, role)`: TLS 1.3 over a TCP link (`medium() == "tcp+tls1.3"`), exposes `channel_binding()`.
  Non-blocking socket + poll; SSL calls serialised by a mutex; `close()` only shuts the socket down.

## Not implemented (needs real hardware / Android)
BLE (GATT/L2CAP CoC), Bluetooth Classic RFCOMM, Wi-Fi Direct, Wi-Fi Aware, external radios.
Each will be a `Link` implementation; devices lacking a medium simply don't register it.
**No radio claims are made from TCP tests.**
