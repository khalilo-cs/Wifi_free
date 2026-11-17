#include "test.hpp"
#include "wavenet/session.hpp"
#include <thread>
using namespace wavenet;
using np::MsgType;

namespace {
struct Pair {
  std::unique_ptr<Session> client, server;
  std::string server_err, client_err;
};
Pair connect_pair(const Identity& a, const Identity& b, const TrustPolicy& pa, const TrustPolicy& pb, uint32_t na = 1, uint32_t nb = 1) {
  TcpListener l("127.0.0.1", 0);
  Pair p;
  std::thread t([&] {
    auto link = l.accept(3000);
    try { if (link) p.server = Session::respond(std::move(link), b, nb, pb); } catch (const std::exception& e) { p.server_err = e.what(); }
  });
  try { p.client = Session::initiate(tcp_connect("127.0.0.1", l.port(), 3000), a, na, pa); } catch (const std::exception& e) { p.client_err = e.what(); }
  t.join();
  return p;
}
}  // namespace

TEST(session_handshake_and_exchange) {
  auto a = Identity::generate(), b = Identity::generate();
  auto p = connect_pair(a, b, {}, {});
  CHECK(p.client && p.server);
  if (!p.client || !p.server) return;
  CHECK(p.client->peer_id() == b.node_id());
  CHECK(p.server->peer_id() == a.node_id());
  p.client->send(MsgType::SEARCH_REQUEST, as_view("query"), 9);
  auto f = p.server->recv(2000);
  CHECK(f.h.type == MsgType::SEARCH_REQUEST && f.h.request_id == 9);
  Bytes big(1 << 20, 0x5a);  // 1 MiB payload over TCP
  p.server->send(MsgType::DATA_CHUNK, big, 10);
  auto g = p.client->recv(5000);
  CHECK(g.payload == big);
}

TEST(session_allowlist_rejects_untrusted) {
  auto a = Identity::generate(), b = Identity::generate(), c = Identity::generate();
  TrustPolicy only_c; only_c.mode = TrustPolicy::Mode::Allowlist; only_c.allow.insert(c.node_id());
  auto p = connect_pair(a, b, {}, only_c);
  CHECK(!p.server); CHECK(!p.client);
  CHECK(p.server_err.find("not trusted") != std::string::npos);
  CHECK(p.client_err.find("not trusted") != std::string::npos);
  TrustPolicy blocked; blocked.block.insert(b.node_id());
  auto q = connect_pair(a, b, blocked, {});
  CHECK(!q.client);  // client refuses blocked server
}

TEST(session_network_id_mismatch) {
  auto a = Identity::generate(), b = Identity::generate();
  auto p = connect_pair(a, b, {}, {}, 1, 2);
  CHECK(!p.client && !p.server);
}

// A link that records frames so we can replay them.
struct RecLink : Link {
  std::unique_ptr<Link> in; std::vector<Bytes>* rec;
  RecLink(std::unique_ptr<Link> l, std::vector<Bytes>* r) : in(std::move(l)), rec(r) {}
  void send_frame(ByteView f) override { rec->emplace_back(f.begin(), f.end()); in->send_frame(f); }
  Bytes recv_frame(int t) override { return in->recv_frame(t); }
  void close() override { in->close(); }
  std::string describe() const override { return in->describe(); }
  const char* medium() const override { return "rec"; }
};

TEST(session_rejects_replay) {
  auto a = Identity::generate(), b = Identity::generate();
  TcpListener l("127.0.0.1", 0);
  std::vector<Bytes> sent;
  std::unique_ptr<Session> srv;
  std::thread t([&] { srv = Session::respond(l.accept(3000), b, 1, {}); });
  auto raw = std::make_unique<RecLink>(tcp_connect("127.0.0.1", l.port(), 3000), &sent);
  auto* rl = raw.get();
  auto cli = Session::initiate(std::move(raw), a, 1, {});
  t.join();
  cli->send(MsgType::SEARCH_REQUEST, as_view("x"), 1);
  CHECK(srv->recv(2000).h.request_id == 1);
  rl->in->send_frame(sent.back());  // replay identical frame
  bool rejected = false;
  try { srv->recv(2000); } catch (const SessionError&) { rejected = true; }
  CHECK(rejected);
}
