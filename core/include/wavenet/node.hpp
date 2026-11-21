// WAVE node: serves catalog/content to authenticated peers; client + multi-source resumable downloader.
#pragma once
#include <atomic>
#include <functional>
#include <list>
#include <map>
#include <thread>
#include "wavenet/catalog.hpp"
#include "wavenet/ratelimit.hpp"
#include "wavenet/session.hpp"
#include "wavenet/store.hpp"

namespace wavenet {

namespace wire {  // application payload codecs
Bytes search_request(const SearchQuery& q);
SearchQuery parse_search_request(ByteView b);
Bytes search_response(const std::vector<Manifest>& ms);
std::vector<Manifest> parse_search_response(ByteView b);  // each manifest signature-verified
Bytes chunk_request(const Hash256& cid, uint32_t idx);
Bytes data_chunk(const Hash256& cid, uint32_t idx, ByteView data);
}  // namespace wire

struct NodeContext {
  const Identity& id;
  Catalog& catalog;
  ContentStore& store;
  uint32_t network_id = 1;
  TrustPolicy trust;
  bool tls = true;  // TLS 1.3 + channel binding on every peer connection (both sides must agree)
};

// Can `ctx` serve content `m` to other peers?
bool servable(const NodeContext& ctx, const CatalogEntry& e);

struct ServerLimits {
  size_t max_conns = 32;            // total concurrent sessions
  size_t max_conns_per_ip = 8;      // concurrent sessions from one address
  double handshake_burst = 20;      // connection attempts allowed in a burst, per address
  double handshakes_per_sec = 5;    // sustained connection attempts per address
  double requests_per_sec = 500;    // per session (burst = 2x)
};

class NodeServer {
 public:
  NodeServer(NodeContext& ctx, const std::string& bind_host, uint16_t port, ServerLimits limits = {});
  ~NodeServer();
  void start();
  void stop();
  uint16_t port() const { return listener_.port(); }
  uint64_t chunks_served() const { return chunks_served_; }
  uint64_t refused_rate() const { return refused_rate_; }      // attempts dropped by the per-address limiter
  uint64_t refused_capacity() const { return refused_cap_; }   // attempts dropped by connection caps
  uint64_t requests_limited() const { return req_limited_; }

 private:
  void accept_loop();
  void serve(std::shared_ptr<Session> s);
  NodeContext& ctx_;
  TcpListener listener_;
  ServerLimits lim_;
  RateLimiter handshake_limiter_;
  std::map<std::string, size_t> per_ip_;
  std::atomic<uint64_t> refused_rate_{0}, refused_cap_{0}, req_limited_{0};
  std::atomic<bool> running_{false};
  std::atomic<size_t> active_{0};
  std::atomic<uint64_t> chunks_served_{0};
  std::thread accept_thread_;
  std::mutex mu_;
  struct Conn { std::shared_ptr<Session> s; std::thread t; bool done = false; std::string ip; };
  std::list<std::shared_ptr<Conn>> conns_;
};

struct PeerAddr { std::string host; uint16_t port; };

// One request in flight at a time per client.
class PeerClient {
 public:
  PeerClient(const PeerAddr& a, NodeContext& ctx, int timeout_ms = 10000);
  std::vector<Manifest> search(const SearchQuery& q);
  std::optional<Manifest> manifest(const Hash256& cid);
  Bytes chunk(const Hash256& cid, uint32_t idx);  // raw, NOT yet verified
  // Pipelining: send now, receive later. The server answers requests on a session in order.
  uint64_t request_chunk(const Hash256& cid, uint32_t idx);
  Bytes receive_chunk(const Hash256& cid, uint32_t idx, uint64_t req);
  NodeId peer_id() const { return s_->peer_id(); }
  std::string describe() const { return s_->link().describe(); }
  bool encrypted() const { return s_->link().encrypted(); }
 private:
  np::Frame call(np::MsgType t, ByteView payload, np::MsgType expect);
  std::unique_ptr<Session> s_;
  int timeout_;
  uint64_t next_req_ = 1;
};

struct DownloadStats {
  bool complete = false;
  uint32_t total_chunks = 0, already_had = 0, fetched = 0, rejected = 0;
  uint64_t bytes_fetched = 0;
  double seconds = 0;
  double first_chunk_seconds = -1;  // time from start until the first verified chunk was stored (TTFB)
  uint32_t window = 0;
  std::map<std::string, uint32_t> per_peer;  // describe() -> chunks delivered
  std::string error;
};

// progress(fetched_so_far, total) — return false to stop (download stays resumable).
using ProgressFn = std::function<bool(uint32_t, uint32_t)>;

struct DownloadOptions {
  uint32_t window = 4;  // outstanding chunk requests per source (1 = no pipelining)
};

DownloadStats download(NodeContext& ctx, const Hash256& cid, const std::vector<PeerAddr>& peers,
                       ProgressFn progress = nullptr, DownloadOptions opt = {});

}  // namespace wavenet
