#include "test.hpp"
#include "wavenet/node.hpp"
#include <unistd.h>
#include <thread>
using namespace wavenet;
namespace fs = std::filesystem;

TEST(ratelimit_token_bucket) {
  RateLimiter r(3, 2);  // burst 3, 2/s
  CHECK(r.allow("a", 1000)); CHECK(r.allow("a", 1000)); CHECK(r.allow("a", 1000));
  CHECK(!r.allow("a", 1000));          // burst exhausted
  CHECK(r.allow("b", 1000));           // other key unaffected
  CHECK(!r.allow("a", 1400));          // 0.8 token refilled: still not enough
  CHECK(r.allow("a", 1500));           // 1.0 token after 500 ms
  CHECK(!r.allow("a", 1500));
  CHECK(r.allow("a", 100000)); CHECK(r.allow("a", 100000)); CHECK(r.allow("a", 100000));
  CHECK(!r.allow("a", 100000));        // refill capped at burst
  CHECK(!r.allow("a", 50));            // clock going backwards gives no tokens
}

TEST(ratelimit_bounded_keys) {
  RateLimiter r(1, 1, 16);
  for (int i = 0; i < 1000; ++i) r.allow("k" + std::to_string(i), uint64_t(i));
  CHECK_EQ(r.tracked(), size_t(16));
}

TEST(server_refuses_connection_flood) {
  auto dir = fs::temp_directory_path() / ("wavenet_rl_" + std::to_string(::getpid()));
  fs::remove_all(dir); fs::create_directories(dir);
  auto id = Identity::generate();
  Catalog cat(dir / "c.db"); ContentStore store(dir / "s");
  NodeContext ctx{id, cat, store, 1, {}};
  ServerLimits lim; lim.handshake_burst = 5; lim.handshakes_per_sec = 1; lim.max_conns_per_ip = 100;
  NodeServer srv(ctx, "127.0.0.1", 0, lim);
  srv.start();
  // 30 raw TCP connections in a burst (no handshake): only ~5 may reach the expensive stage.
  std::vector<std::unique_ptr<Link>> raw;
  for (int i = 0; i < 30; ++i) raw.push_back(tcp_connect("127.0.0.1", srv.port(), 2000));
  for (int i = 0; i < 50 && srv.refused_rate() < 25; ++i) std::this_thread::sleep_for(std::chrono::milliseconds(20));
  CHECK(srv.refused_rate() >= 25u);
  CHECK(srv.refused_rate() <= 25u);
  raw.clear();
  // After the bucket refills, a legitimate peer gets through.
  std::this_thread::sleep_for(std::chrono::milliseconds(1100));
  auto other = Identity::generate();
  Catalog c2(dir / "c2.db"); ContentStore s2(dir / "s2");
  NodeContext cctx{other, c2, s2, 1, {}};
  bool ok = false;
  try { PeerClient pc({"127.0.0.1", srv.port()}, cctx); ok = pc.search({"", "", false}).empty(); } catch (const std::exception& e) { std::printf("  %s\n", e.what()); }
  CHECK(ok);
  srv.stop();
  fs::remove_all(dir);
}

TEST(server_per_ip_connection_cap_and_request_limit) {
  auto dir = fs::temp_directory_path() / ("wavenet_rl2_" + std::to_string(::getpid()));
  fs::remove_all(dir); fs::create_directories(dir);
  auto id = Identity::generate();
  Catalog cat(dir / "c.db"); ContentStore store(dir / "s");
  NodeContext ctx{id, cat, store, 1, {}};
  ServerLimits lim; lim.max_conns_per_ip = 2; lim.requests_per_sec = 10;
  NodeServer srv(ctx, "127.0.0.1", 0, lim);
  srv.start();
  auto cid = Identity::generate();
  Catalog c2(dir / "c2.db"); ContentStore s2(dir / "s2");
  NodeContext cctx{cid, c2, s2, 1, {}};
  PeerClient p1({"127.0.0.1", srv.port()}, cctx), p2({"127.0.0.1", srv.port()}, cctx);
  bool third_ok = true;
  try { PeerClient p3({"127.0.0.1", srv.port()}, cctx); p3.search({}); } catch (...) { third_ok = false; }
  CHECK(!third_ok);
  CHECK(srv.refused_capacity() >= 1u);
  // Request flood on one session: burst 20 then refused and closed.
  int served = 0; bool limited = false;
  for (int i = 0; i < 100; ++i) {
    try { p1.search({}); ++served; } catch (const std::exception& e) { limited = std::string(e.what()).find("rate limited") != std::string::npos; break; }
  }
  CHECK(limited);
  CHECK(served >= 15 && served <= 25);
  CHECK(srv.requests_limited() == 1u);
  CHECK(p2.search({}).empty());  // other session unaffected
  srv.stop();
  fs::remove_all(dir);
}
