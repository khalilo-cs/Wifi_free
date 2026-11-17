// Mutually authenticated WAVE-NP session over any Link.
#pragma once
#include <mutex>
#include <set>
#include "wavenet/protocol.hpp"
#include "wavenet/transport.hpp"

namespace wavenet {

struct TrustPolicy {
  enum class Mode { Open, Allowlist } mode = Mode::Open;
  std::set<NodeId> allow;   // used in Allowlist mode
  std::set<NodeId> block;   // always rejected
  bool permits(const NodeId& id) const {
    if (block.count(id)) return false;
    return mode == Mode::Open || allow.count(id) > 0;
  }
};

struct SessionError : std::runtime_error { using std::runtime_error::runtime_error; };

constexpr uint64_t kMaxClockSkewMs = 5 * 60 * 1000;
constexpr int kHandshakeTimeoutMs = 5000;

class Session {
 public:
  static std::unique_ptr<Session> initiate(std::unique_ptr<Link> link, const Identity& me, uint32_t network_id,
                                           const TrustPolicy& policy);
  static std::unique_ptr<Session> respond(std::unique_ptr<Link> link, const Identity& me, uint32_t network_id,
                                          const TrustPolicy& policy);

  void send(np::MsgType type, ByteView payload, uint64_t request_id = 0);  // thread-safe
  np::Frame recv(int timeout_ms);  // validated: signature/context, src == peer, seq increasing, network id
  const PublicKey& peer_key() const { return peer_; }
  NodeId peer_id() const { return node_id_of(peer_); }
  const Link& link() const { return *link_; }
  void close() { link_->close(); }

 private:
  Session(std::unique_ptr<Link> l, const Identity& me, uint32_t net) : link_(std::move(l)), me_(me), net_(net) {}
  np::Frame make(np::MsgType t, ByteView payload, uint64_t req);
  std::unique_ptr<Link> link_;
  const Identity& me_;
  uint32_t net_;
  PublicKey peer_{};
  Bytes my_nonce_, peer_nonce_;
  uint64_t send_seq_ = 0, recv_seq_ = 0;
  std::mutex send_mu_;
};

}  // namespace wavenet
