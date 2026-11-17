#include "wavenet/discovery.hpp"
#include <arpa/inet.h>
#include <netinet/in.h>
#include <poll.h>
#include <sys/socket.h>
#include <unistd.h>
#include <cerrno>
#include <cstring>
#include "wavenet/transport.hpp"

namespace wavenet {

Bytes Announce::encode() const {
  Writer w; w.u16(tcp_port); w.u32(roles); w.u32(content_count); w.str(name.substr(0, 64)); return w.take();
}
Announce Announce::decode(ByteView b) {
  Reader r(b, 64); Announce a;
  a.tcp_port = r.u16(); a.roles = r.u32(); a.content_count = r.u32(); a.name = r.str(); r.expect_done();
  if (a.tcp_port == 0) throw DecodeError("announce without port");
  return a;
}

Discovery::Discovery(const Identity& me, uint32_t net, DiscoveryConfig cfg) : me_(me), net_(net), cfg_(std::move(cfg)) {
  fd_ = ::socket(AF_INET, SOCK_DGRAM | SOCK_CLOEXEC, 0);
  if (fd_ < 0) throw TransportError("udp socket failed");
  int one = 1;
  ::setsockopt(fd_, SOL_SOCKET, SO_REUSEADDR, &one, sizeof one);
  ::setsockopt(fd_, SOL_SOCKET, SO_REUSEPORT, &one, sizeof one);
  sockaddr_in a{}; a.sin_family = AF_INET; a.sin_port = htons(cfg_.port); a.sin_addr.s_addr = htonl(INADDR_ANY);
  if (::bind(fd_, reinterpret_cast<sockaddr*>(&a), sizeof a) != 0) {
    ::close(fd_); throw TransportError(std::string("udp bind failed: ") + std::strerror(errno));
  }
  sockaddr_in got{}; socklen_t gl = sizeof got;
  ::getsockname(fd_, reinterpret_cast<sockaddr*>(&got), &gl);
  bound_port_ = ntohs(got.sin_port);
  if (cfg_.multicast) {
    ip_mreq m{};
    if (::inet_pton(AF_INET, cfg_.group.c_str(), &m.imr_multiaddr) != 1 ||
        ::inet_pton(AF_INET, cfg_.iface_addr.c_str(), &m.imr_interface) != 1) {
      ::close(fd_); throw TransportError("bad multicast/interface address");
    }
    if (::setsockopt(fd_, IPPROTO_IP, IP_ADD_MEMBERSHIP, &m, sizeof m) != 0) {
      ::close(fd_); throw TransportError(std::string("multicast join failed: ") + std::strerror(errno));
    }
    ::setsockopt(fd_, IPPROTO_IP, IP_MULTICAST_IF, &m.imr_interface, sizeof m.imr_interface);
    unsigned char loop = 1, ttl = 1;  // stay on the local link
    ::setsockopt(fd_, IPPROTO_IP, IP_MULTICAST_LOOP, &loop, sizeof loop);
    ::setsockopt(fd_, IPPROTO_IP, IP_MULTICAST_TTL, &ttl, sizeof ttl);
  }
}

Discovery::~Discovery() { if (fd_ >= 0) ::close(fd_); }

void Discovery::announce(const Announce& ann) {
  np::Frame f; f.h.type = np::MsgType::NODE_ANNOUNCE; f.h.network_id = net_; f.h.src = me_.public_key();
  f.h.timestamp_ms = np::now_ms(); f.h.seq = f.h.timestamp_ms; f.h.hop_limit = 1; f.payload = ann.encode();
  auto wire = np::encode(f, me_);
  auto send_to = [&](const std::string& ip, uint16_t port) {
    sockaddr_in d{}; d.sin_family = AF_INET; d.sin_port = htons(port);
    if (::inet_pton(AF_INET, ip.c_str(), &d.sin_addr) != 1) return;
    ::sendto(fd_, wire.data(), wire.size(), 0, reinterpret_cast<sockaddr*>(&d), sizeof d);
  };
  if (cfg_.multicast) send_to(cfg_.group, cfg_.port);
  for (auto& [ip, port] : cfg_.seeds) send_to(ip, port);
}

bool Discovery::handle_datagram(ByteView d, const std::string& from_ip) {
  std::lock_guard lk(mu_);
  auto reject = [&] { ++stats_.rejected; return false; };
  if (d.size() > 2048) return reject();
  np::Frame f;
  Announce a;
  try { f = np::decode(d); a = Announce::decode(f.payload); } catch (const DecodeError&) { return reject(); }
  if (f.h.type != np::MsgType::NODE_ANNOUNCE || f.h.network_id != net_) return reject();
  if (f.h.src == me_.public_key()) return false;  // our own multicast echo; not an error
  uint64_t now = np::now_ms();
  if (f.h.timestamp_ms + cfg_.max_skew_ms < now || f.h.timestamp_ms > now + cfg_.max_skew_ms) return reject();
  auto id = f.src_id();
  auto it = peers_.find(id);
  if (it != peers_.end() && f.h.timestamp_ms <= it->second.last_ts) return reject();  // replay / stale
  if (it == peers_.end() && peers_.size() >= cfg_.max_peers) {
    // evict the stalest entry to bound memory
    auto old = peers_.begin();
    for (auto j = peers_.begin(); j != peers_.end(); ++j) if (j->second.last_seen_ms < old->second.last_seen_ms) old = j;
    peers_.erase(old);
  }
  PeerInfo& p = peers_[id];
  p.key = f.h.src; p.id = id; p.ip = from_ip; p.ann = a; p.last_ts = f.h.timestamp_ms; p.last_seen_ms = now;
  ++stats_.accepted;
  return true;
}

int Discovery::poll(int timeout_ms) {
  int accepted = 0;
  uint64_t deadline = np::now_ms() + uint64_t(std::max(timeout_ms, 0));
  Bytes buf(4096);
  for (;;) {
    int64_t left = int64_t(deadline) - int64_t(np::now_ms());
    if (left < 0) left = 0;
    pollfd p{fd_, POLLIN, 0};
    int r = ::poll(&p, 1, int(left));
    if (r <= 0) break;
    sockaddr_in from{}; socklen_t fl = sizeof from;
    ssize_t n = ::recvfrom(fd_, buf.data(), buf.size(), 0, reinterpret_cast<sockaddr*>(&from), &fl);
    if (n <= 0) continue;
    char ip[INET_ADDRSTRLEN] = {0};
    ::inet_ntop(AF_INET, &from.sin_addr, ip, sizeof ip);
    if (handle_datagram(ByteView(buf.data(), size_t(n)), ip)) ++accepted;
    if (left == 0) break;
  }
  return accepted;
}

std::vector<PeerInfo> Discovery::peers() const {
  std::lock_guard lk(mu_);
  std::vector<PeerInfo> out;
  uint64_t now = np::now_ms();
  for (auto& [id, p] : peers_) if (p.last_seen_ms + cfg_.expiry_ms >= now) out.push_back(p);
  return out;
}

}  // namespace wavenet
