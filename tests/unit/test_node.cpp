#include "test.hpp"
#include "wavenet/node.hpp"
#include <unistd.h>
#include <fstream>
#include <thread>
using namespace wavenet;
namespace fs = std::filesystem;

namespace {
fs::path root() {
  static fs::path r = [] { auto p = fs::temp_directory_path() / ("wavenet_node_" + std::to_string(::getpid())); fs::remove_all(p); return p; }();
  return r;
}
struct TestNode {
  fs::path dir;
  Identity id;
  Catalog cat;
  ContentStore store;
  NodeContext ctx;
  std::unique_ptr<NodeServer> srv;
  explicit TestNode(const std::string& n)
      : dir(root() / n), id((fs::create_directories(dir), Identity::generate())), cat(dir / "catalog.db"),
        store(dir / "store"), ctx{id, cat, store, 1, {}} {}
  PeerAddr serve() { srv = std::make_unique<NodeServer>(ctx, "127.0.0.1", 0); srv->start(); return {"127.0.0.1", srv->port()}; }
  Manifest publish(const fs::path& f, ContentMeta meta = {}) {
    auto m = store.import_file(f, meta, id, kMinChunk); cat.put(m, LocalState::Published); return m;
  }
};
fs::path make_file(const std::string& name, size_t n, uint8_t salt) {
  fs::create_directories(root());
  auto p = root() / name; std::ofstream f(p, std::ios::binary);
  uint32_t x = 2463534242u ^ salt;
  for (size_t i = 0; i < n; ++i) { x ^= x << 13; x ^= x >> 17; x ^= x << 5; f.put(char(x & 0xff)); }
  return p;
}
}  // namespace

TEST(node_publish_search_download_verify) {
  TestNode a("a1"), e("e1");
  auto f = make_file("book.pdf", 300000, 1);
  ContentMeta meta; meta.title = "Physics Book"; meta.category = "books"; meta.keywords = "science";
  auto m = a.publish(f, meta);
  ContentMeta pm; pm.title = "Physics private diary"; pm.visibility = Visibility::Private;
  a.publish(make_file("diary.txt", 1000, 2), pm);
  auto addr = a.serve();
  PeerClient c(addr, e.ctx);
  auto res = c.search({"physics", "", false});
  CHECK_EQ(res.size(), size_t(1));  // private item not exposed
  if (!res.empty()) CHECK(res[0].content_id == m.content_id);
  auto st = download(e.ctx, m.content_id, {addr});
  CHECK(st.complete); CHECK_EQ(st.fetched, m.chunk_count()); CHECK_EQ(st.rejected, 0u);
  CHECK(sha256_file(e.store.blob_path(m.content_id)) == sha256_file(f));
  CHECK(e.cat.get(m.content_id)->state == LocalState::Downloaded);
  auto again = download(e.ctx, m.content_id, {addr});  // already complete: nothing fetched
  CHECK(again.complete); CHECK_EQ(again.fetched, 0u);
}

TEST(node_resume_after_interrupt) {
  TestNode a("a2"), e("e2");
  auto f = make_file("video.mp4", 500000, 3);
  auto m = a.publish(f);
  auto addr = a.serve();
  auto st1 = download(e.ctx, m.content_id, {addr}, [](uint32_t done, uint32_t) { return done < 10; });
  CHECK(!st1.complete);
  // Stop honours in-flight requests: at most window-1 (=3) extra verified chunks arrive after the stop.
  CHECK(st1.fetched >= 10u && st1.fetched <= 13u);
  // fresh store/catalog objects = simulated process restart
  Catalog cat2(e.dir / "catalog.db"); ContentStore store2(e.dir / "store");
  NodeContext ctx2{e.id, cat2, store2, 1, {}};
  auto st2 = download(ctx2, m.content_id, {addr});
  CHECK(st2.complete);
  CHECK_EQ(st2.already_had, st1.fetched);
  CHECK_EQ(st2.fetched, m.chunk_count() - st1.fetched);  // correct chunks are not fetched again
  // window=1 (no pipelining) stops exactly
  TestNode e1("e2w1");
  auto s1 = download(e1.ctx, m.content_id, {addr}, [](uint32_t done, uint32_t) { return done < 10; }, {1});
  CHECK_EQ(s1.fetched, 10u);
  CHECK(sha256_file(store2.blob_path(m.content_id)) == m.content_id);
}

TEST(node_multi_source_and_source_failure) {
  TestNode a("a3"), c("c3"), e("e3");
  auto f = make_file("app.apk", 800000, 4);
  auto m = a.publish(f);
  auto aa = a.serve();
  CHECK(download(c.ctx, m.content_id, {aa}).complete);  // C caches a replica (redistribution allowed)
  auto ca = c.serve();
  auto st = download(e.ctx, m.content_id, {aa, ca});
  CHECK(st.complete);
  CHECK_EQ(st.per_peer.size(), size_t(2));  // both sources delivered chunks
  uint32_t sum = 0; for (auto& [k, v] : st.per_peer) sum += v;
  CHECK_EQ(sum, m.chunk_count());

  // Source A disappears mid-download; resume from replica C.
  TestNode e2("e3b");
  auto st1 = download(e2.ctx, m.content_id, {aa}, [&](uint32_t done, uint32_t) { if (done == 5) a.srv->stop(); return true; });
  CHECK(!st1.complete);
  CHECK(st1.fetched >= 5u && st1.fetched < m.chunk_count());
  auto st2 = download(e2.ctx, m.content_id, {aa, ca});  // A is down, C serves the rest
  CHECK(st2.complete);
  CHECK_EQ(st2.already_had, st1.fetched);
  CHECK(sha256_file(e2.store.blob_path(m.content_id)) == sha256_file(f));
}

TEST(node_rejects_corrupt_peer) {
  TestNode a("a4"), e("e4");
  auto f = make_file("doc.bin", 200000, 5);
  auto m = a.publish(f);
  auto good = a.serve();
  // Malicious node: authenticates correctly, returns the genuine manifest, but corrupts every chunk.
  Identity evil = Identity::generate();
  TcpListener l("127.0.0.1", 0);
  std::atomic<bool> run{true};
  std::thread bad([&] {
    while (run) {
      auto link = l.accept(100);
      if (!link) continue;
      try {
        auto s = Session::respond(tls_wrap(std::move(link), TlsRole::Server), evil, 1, {});
        for (;;) {
          auto fr = s->recv(2000);
          if (fr.h.type == np::MsgType::MANIFEST_REQUEST) {
            Writer w; w.u8(1); w.blob(m.encode()); s->send(np::MsgType::MANIFEST_RESPONSE, w.data(), fr.h.request_id);
          } else if (fr.h.type == np::MsgType::CHUNK_REQUEST) {
            Reader r(fr.payload); auto cid = r.fixed<32>(); uint32_t idx = r.u32();
            auto d = a.store.read_chunk(m, idx); d[d.size() / 2] ^= 0x01;
            s->send(np::MsgType::DATA_CHUNK, wire::data_chunk(cid, idx, d), fr.h.request_id);
          }
        }
      } catch (...) {}
    }
  });
  auto st = download(e.ctx, m.content_id, {{"127.0.0.1", l.port()}, good});
  run = false; bad.join();
  CHECK(st.complete);
  CHECK(st.rejected >= 1u);
  CHECK(sha256_file(e.store.blob_path(m.content_id)) == sha256_file(f));
}

TEST(node_redistribution_and_trust) {
  TestNode a("a5"), c("c5"), e("e5");
  ContentMeta nr; nr.title = "No redistribution"; nr.allow_redistribution = false;
  auto m = a.publish(make_file("nr.bin", 50000, 6), nr);
  auto aa = a.serve();
  CHECK(download(c.ctx, m.content_id, {aa}).complete);
  auto ca = c.serve();
  auto st = download(e.ctx, m.content_id, {ca});  // C must not re-serve
  CHECK(!st.complete);
  // Allowlist-mode node refuses an unknown peer
  TestNode g("g5");
  g.ctx.trust.mode = TrustPolicy::Mode::Allowlist;
  auto m2 = g.publish(make_file("g.bin", 20000, 7));
  auto ga = g.serve();
  auto st2 = download(e.ctx, m2.content_id, {ga});
  CHECK(!st2.complete);
  CHECK(st2.error.find("not trusted") != std::string::npos);
  g.ctx.trust.allow.insert(e.id.node_id());
  CHECK(download(e.ctx, m2.content_id, {ga}).complete);
}
