// WAVE node: serves catalog/content to authenticated peers; client + multi-source resumable downloader.
#pragma once
#include <atomic>
#include <functional>
#include <list>
#include <map>
#include <thread>
#include "wavenet/catalog.hpp"
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
};

// Can `ctx` serve content `m` to other peers?
bool servable(const NodeContext& ctx, const CatalogEntry& e);

class NodeServer {
 public:
  NodeServer(NodeContext& ctx, const std::string& bind_host, uint16_t port, size_t max_conns = 32);
  ~NodeServer();
  void start();
  void stop();
  uint16_t port() const { return listener_.port(); }
  uint64_t chunks_served() const { return chunks_served_; }

 private:
  void accept_loop();
  void serve(std::shared_ptr<Session> s);
  NodeContext& ctx_;
  TcpListener listener_;
  size_t max_conns_;
  std::atomic<bool> running_{false};
  std::atomic<size_t> active_{0};
  std::atomic<uint64_t> chunks_served_{0};
  std::thread accept_thread_;
  std::mutex mu_;
  struct Conn { std::shared_ptr<Session> s; std::thread t; bool done = false; };
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
  NodeId peer_id() const { return s_->peer_id(); }
  std::string describe() const { return s_->link().describe(); }
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
  std::map<std::string, uint32_t> per_peer;  // describe() -> chunks delivered
  std::string error;
};

// progress(fetched_so_far, total) — return false to stop (download stays resumable).
using ProgressFn = std::function<bool(uint32_t, uint32_t)>;

DownloadStats download(NodeContext& ctx, const Hash256& cid, const std::vector<PeerAddr>& peers,
                       ProgressFn progress = nullptr);

}  // namespace wavenet
