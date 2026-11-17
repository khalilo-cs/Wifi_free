// W2 Discovery: signed NODE_ANNOUNCE over UDP (multicast and/or unicast seeds).
#pragma once
#include <map>
#include <mutex>
#include <string>
#include <vector>
#include "wavenet/protocol.hpp"

namespace wavenet {

enum Role : uint32_t { ROLE_CLIENT = 1, ROLE_PUBLISHER = 2, ROLE_HOST = 4, ROLE_RELAY = 8, ROLE_CACHE = 16, ROLE_GATEWAY = 32 };

struct Announce {
  uint16_t tcp_port = 0;
  uint32_t roles = ROLE_CLIENT;
  uint32_t content_count = 0;
  std::string name;  // display name, max 64 bytes
  Bytes encode() const;
  static Announce decode(ByteView b);
};

struct PeerInfo {
  PublicKey key{};
  NodeId id{};
  std::string ip;
  Announce ann;
  uint64_t last_ts = 0;      // sender timestamp of newest accepted announce
  uint64_t last_seen_ms = 0; // local receive time
};

struct DiscoveryConfig {
  std::string group = "239.255.77.86";  // admin-scoped IPv4 multicast
  uint16_t port = 47800;
  std::string iface_addr = "0.0.0.0";   // local interface IPv4 for multicast (e.g. 127.0.0.1)
  bool multicast = true;
  std::vector<std::pair<std::string, uint16_t>> seeds;  // unicast targets
  uint64_t max_skew_ms = 60'000;
  uint64_t expiry_ms = 30'000;
  size_t max_peers = 1024;
};

class Discovery {
 public:
  Discovery(const Identity& me, uint32_t network_id, DiscoveryConfig cfg);
  ~Discovery();
  void announce(const Announce& a);
  // Receives and processes datagrams for up to timeout_ms. Returns number of accepted announces.
  int poll(int timeout_ms);
  std::vector<PeerInfo> peers() const;  // non-expired
  // Processes one raw datagram (exposed for tests). Returns true if accepted.
  bool handle_datagram(ByteView d, const std::string& from_ip);
  uint16_t bound_port() const { return bound_port_; }
  struct Stats { uint64_t accepted = 0, rejected = 0; };
  Stats stats() const { std::lock_guard lk(mu_); return stats_; }

 private:
  const Identity& me_;
  uint32_t net_;
  DiscoveryConfig cfg_;
  int fd_ = -1;
  uint16_t bound_port_ = 0;
  mutable std::mutex mu_;
  std::map<NodeId, PeerInfo> peers_;
  Stats stats_;
};

}  // namespace wavenet
