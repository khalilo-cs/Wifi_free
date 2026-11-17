#include "test.hpp"
#include "wavenet/catalog.hpp"
#include <unistd.h>
using namespace wavenet;
namespace fs = std::filesystem;

static Manifest mk(const Identity& id, const std::string& title, const std::string& cat, const std::string& kw,
                   Visibility v = Visibility::Public, uint8_t seed = 0) {
  Manifest m; m.size = 1; m.chunk_size = kMinChunk; m.chunks.resize(1); m.file_name = title + ".bin";
  m.content_id = sha256(as_view(title + char(seed))); m.meta.title = title; m.meta.category = cat;
  m.meta.keywords = kw; m.meta.visibility = v; m.created_ms = 1000 + seed; m.sign(id); return m;
}

TEST(catalog_put_get_search) {
  auto p = fs::temp_directory_path() / ("wavenet_cat_" + std::to_string(::getpid()) + ".db");
  fs::remove(p);
  auto id = Identity::generate();
  {
    Catalog c(p);
    auto a = mk(id, "Arabic Grammar", "books", "language nahw", Visibility::Public, 1);
    auto b = mk(id, "Solar Video", "videos", "energy sun", Visibility::Public, 2);
    auto s = mk(id, "Secret Notes", "documents", "private", Visibility::Private, 3);
    auto r = mk(id, "Remote Thing", "books", "language", Visibility::Public, 4);
    c.put(a, LocalState::Published); c.put(b, LocalState::Published);
    c.put(s, LocalState::Published); c.put(r, LocalState::Remote);
    CHECK_EQ(c.count(), size_t(4));
    CHECK_EQ(c.search({"grammar", "", false}).size(), size_t(1));
    CHECK_EQ(c.search({"GRAMMAR arabic", "", false}).size(), size_t(1));  // case-insensitive ASCII, AND
    CHECK_EQ(c.search({"language", "", false}).size(), size_t(2));
    CHECK_EQ(c.search({"language", "", true}).size(), size_t(1));  // remote-only entry not re-advertised
    CHECK_EQ(c.search({"", "books", false}).size(), size_t(2));
    CHECK_EQ(c.search({"secret", "", true}).size(), size_t(0));    // private hidden from peers
    CHECK_EQ(c.search({"secret", "", false}).size(), size_t(1));
    CHECK_EQ(c.search({"%", "", false}).size(), size_t(0));        // wildcard escaped
    CHECK_EQ(c.search({"'; DROP TABLE content; --", "", false}).size(), size_t(0));
    CHECK_EQ(c.search({"", "", false, 2, 0}).size(), size_t(2));   // pagination
    CHECK_EQ(c.search({"", "", false, 50, 3}).size(), size_t(1));
    auto g = c.get(a.content_id);
    CHECK(g.has_value() && g->manifest.meta.title == "Arabic Grammar");
    c.put(r, LocalState::Downloaded);  // state upgrades Remote -> Downloaded
    CHECK(c.get(r.content_id)->state == LocalState::Downloaded);
    c.put(r, LocalState::Remote);      // never downgrades
    CHECK(c.get(r.content_id)->state == LocalState::Downloaded);
    CHECK(c.remove(b.content_id, id.public_key()));
    CHECK(!c.get(b.content_id).has_value());
  }
  { Catalog c(p); CHECK_EQ(c.count(), size_t(3)); }  // persisted
  fs::remove(p); fs::remove(p.string() + "-wal"); fs::remove(p.string() + "-shm");
}
