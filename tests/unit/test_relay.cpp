#include "test.hpp"
#include "tap.hpp"
#include "wavenet/node.hpp"
#include <fstream>
using namespace wavenet;
namespace fs = std::filesystem;

namespace {
fs::path rroot() {
  static fs::path r = [] { auto p = fs::temp_directory_path() / ("wavenet_relay_" + std::to_string(::getpid())); fs::remove_all(p); fs::create_directories(p); return p; }();
  return r;
}
struct RNode {
  fs::path dir; Identity id; Catalog cat; ContentStore store; NodeContext ctx; std::unique_ptr<NodeServer> srv;
  explicit RNode(const std::string& n) : dir(rroot() / n), id((fs::create_directories(dir), Identity::generate())),
      cat(dir / "c.db"), store(dir / "s"), ctx{id, cat, store, 1, {}} {}
  PeerAddr serve(bool relay = false, std::map<NodeId, PeerAddr> routes = {}) {
    ServerLimits lim; lim.relay = relay;
    srv = std::make_unique<NodeServer>(ctx, "127.0.0.1", 0, lim);
    srv->set_resolver([routes](const NodeId& nid) -> std::optional<PeerAddr> {
      auto it = routes.find(nid); if (it == routes.end()) return std::nullopt; return it->second; });
    srv->start();
    return {"127.0.0.1", srv->port()};
  }
};
const std::string kSecret = "RELAY-MUST-NOT-SEE-THIS-PLAINTEXT-0042";
fs::path secret_file() {
  auto p = rroot() / "secret.txt";
  std::ofstream f(p); for (int i = 0; i < 2000; ++i) f << kSecret << " line " << i << "\n";
  return p;
}
}  // namespace

TEST(relay_two_and_three_hops) {
  RNode a("a"), b("b"), c("c"), e("e");
  auto m = a.store.import_file(secret_file(), {}, a.id, kMinChunk); a.cat.put(m, LocalState::Published);
  auto aa = a.serve(false);
  auto ba = b.serve(true, {{a.id.node_id(), aa}});
  auto ca = c.serve(true, {{b.id.node_id(), ba}});
  // 2 hops: E -> B -> A
  PeerAddr via_b = ba; via_b.via = {a.id.node_id()};
  { PeerClient pc(via_b, e.ctx); CHECK(pc.peer_id() == a.id.node_id()); CHECK(pc.encrypted()); CHECK_EQ(pc.relay_hops(), 1u); }
  auto st = download(e.ctx, m.content_id, {via_b});
  CHECK(st.complete);
  CHECK(sha256_file(e.store.blob_path(m.content_id)) == m.content_id);
  CHECK(b.srv->relays_established() >= 2u);
  // 3 hops: E2 -> C -> B -> A
  RNode e2("e2");
  PeerAddr via_cb = ca; via_cb.via = {b.id.node_id(), a.id.node_id()};
  auto st2 = download(e2.ctx, m.content_id, {via_cb});
  CHECK(st2.complete);
  CHECK(sha256_file(e2.store.blob_path(m.content_id)) == m.content_id);
  CHECK(c.srv->relays_established() >= 1u);
  CHECK_EQ(a.srv->relays_established(), 0u);  // A never relays: it is the endpoint
}

TEST(relay_refusals) {
  RNode a("ra"), b("rb"), d("rd"), e("re");
  auto aa = a.serve(false);
  auto da = d.serve(false, {{a.id.node_id(), aa}});   // relaying NOT enabled by owner
  auto ba = b.serve(true, {});                        // relay that knows nobody
  auto err_of = [&](PeerAddr p) { try { PeerClient pc(p, e.ctx); return std::string("connected"); } catch (const std::exception& x) { return std::string(x.what()); } };
  PeerAddr p1 = da; p1.via = {a.id.node_id()};
  CHECK(err_of(p1).find("relaying disabled") != std::string::npos);
  PeerAddr p2 = ba; p2.via = {a.id.node_id()};
  CHECK(err_of(p2).find("target unknown") != std::string::npos);
  PeerAddr p3 = ba; p3.via = {b.id.node_id()};
  CHECK(err_of(p3).find("relay itself") != std::string::npos);
  CHECK(d.srv->relays_refused() >= 1u);
}

TEST(relay_fake_target_detected) {
  RNode a("fa"), g("fg"), b("fb"), e("fe");
  a.serve(false);
  auto ga = g.serve(false);
  auto ba = b.serve(true, {{a.id.node_id(), ga}});  // malicious/misconfigured relay sends E to G instead of A
  PeerAddr p = ba; p.via = {a.id.node_id()};
  std::string err;
  try { PeerClient pc(p, e.ctx); err = "connected"; } catch (const std::exception& x) { err = x.what(); }
  CHECK(err.find("unexpected node") != std::string::npos);
}

TEST(relay_sees_no_plaintext) {
  RNode a("ta"), b("tb"), e("te");
  auto m = a.store.import_file(secret_file(), {}, a.id, kMinChunk); a.cat.put(m, LocalState::Published);
  auto aa = a.serve(false);
  Tap tap(aa.port);  // records every byte the relay sends to / receives from A
  auto ba = b.serve(true, {{a.id.node_id(), {"127.0.0.1", tap.l.port()}}});
  PeerAddr p = ba; p.via = {a.id.node_id()};
  auto st = download(e.ctx, m.content_id, {p}, nullptr, {4});
  CHECK(st.complete);
  b.srv->stop();
  tap.t.join();
  CHECK(tap.seen.size() > m.size);    // the whole transfer passed through the tap
  CHECK(!tap.saw(kSecret));           // ...but only as ciphertext
  std::ifstream f(secret_file()); std::string first; std::getline(f, first);
  CHECK(first.find(kSecret) == 0);    // sanity: the secret really is in the file
}

TEST(relay_plaintext_mode_not_mistaken_for_route_request) {
  // Regression: a plaintext session starts with a WAVE-NP HELLO frame; it must not be treated as a relay request.
  RNode a("pa"), b("pb"), e("pe");
  a.ctx.tls = false; e.ctx.tls = false;
  auto m = a.store.import_file(secret_file(), {}, a.id, kMinChunk); a.cat.put(m, LocalState::Published);
  auto aa = a.serve(false);
  auto ba = b.serve(true, {{a.id.node_id(), aa}});
  CHECK(download(e.ctx, m.content_id, {aa}).complete);  // direct
  RNode e2("pe2"); e2.ctx.tls = false;
  PeerAddr p = ba; p.via = {a.id.node_id()};
  CHECK(download(e2.ctx, m.content_id, {p}).complete);  // through relay
}
