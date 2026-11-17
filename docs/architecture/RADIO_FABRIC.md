# Radio Fabric / Transport (T6)

## Implemented
- `Link` interface: reliable ordered frame link (`send_frame`, `recv_frame(timeout)`, `medium()`).
- `TcpLink` / `TcpListener` / `tcp_connect` (IPv4/IPv6, timeouts, TCP_NODELAY, frame size checked
  from the header before allocating — max ≈ 1.06 MiB).

## Not implemented (needs real hardware / Android)
BLE (GATT/L2CAP CoC), Bluetooth Classic RFCOMM, Wi-Fi Direct, Wi-Fi Aware, external radios.
Each will be a `Link` implementation; devices lacking a medium simply don't register it.
**No radio claims are made from TCP tests.**
