#include "wavenet/relay.hpp"
#include <poll.h>
#include <sys/socket.h>
#include "wavenet/session.hpp"

namespace wavenet {

std::unique_ptr<Link> route_through(std::unique_ptr<Link> raw, const Identity& me, uint32_t net, const NodeId& next,
                                    int timeout_ms) {
  Bytes nonce(16); random_bytes(nonce.data(), nonce.size());
  np::Frame f; f.h.type = np::MsgType::ROUTE_PROPOSAL; f.h.network_id = net; f.h.src = me.public_key();
  f.h.timestamp_ms = np::now_ms(); f.h.seq = 1; f.h.hop_limit = 1;
  Writer w; w.raw(next); w.raw(nonce); f.payload = w.take();
  raw->send_frame(np::encode(f, me));
  np::Frame r;
  try { r = np::decode(raw->recv_frame(timeout_ms), nonce); }
  catch (const DecodeError& e) { throw SessionError(std::string("relay reply invalid: ") + e.what()); }
  if (r.h.type == np::MsgType::ERROR) throw SessionError("relay refused: " + std::string(r.payload.begin(), r.payload.end()));
  if (r.h.type != np::MsgType::ROUTE_COMMIT || r.payload != Bytes(next.begin(), next.end()))
    throw SessionError("unexpected relay reply");
  return raw;
}

bool is_route_request(ByteView p) {
  if (p.size() < kRouteSniffLen) return false;
  Reader r(p);
  return r.u32() == np::kMagic && r.u8() == np::kVersion && r.u16() == uint16_t(np::MsgType::ROUTE_PROPOSAL);
}

uint64_t splice_fds(int a, int b, int idle_ms, const std::atomic<bool>& stop) {
  uint8_t buf[65536];
  uint64_t total = 0;
  int idle = 0;
  for (;;) {
    if (stop) break;
    pollfd p[2] = {{a, POLLIN, 0}, {b, POLLIN, 0}};
    int r = ::poll(p, 2, 200);
    if (r < 0) break;
    if (r == 0) { idle += 200; if (idle >= idle_ms) break; continue; }
    idle = 0;
    bool closed = false;
    for (int i = 0; i < 2 && !closed; ++i) {
      if (!(p[i].revents & (POLLIN | POLLHUP | POLLERR))) continue;
      ssize_t n = ::recv(p[i].fd, buf, sizeof buf, 0);
      if (n <= 0) { closed = true; break; }
      int dst = i == 0 ? b : a;
      ssize_t off = 0;
      while (off < n) {
        ssize_t s = ::send(dst, buf + off, size_t(n - off), MSG_NOSIGNAL);
        if (s <= 0) { closed = true; break; }
        off += s;
      }
      total += uint64_t(n);
    }
    if (closed) break;
  }
  ::shutdown(a, SHUT_RDWR); ::shutdown(b, SHUT_RDWR);
  return total;
}

}  // namespace wavenet
