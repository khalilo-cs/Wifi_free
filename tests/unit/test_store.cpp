#include "test.hpp"
#include "wavenet/store.hpp"
#include <unistd.h>
#include <fstream>
using namespace wavenet;
namespace fs = std::filesystem;

static fs::path tdir(const char* n) {
  auto p = fs::temp_directory_path() / (std::string("wavenet_store_") + n + std::to_string(::getpid()));
  fs::remove_all(p); fs::create_directories(p); return p;
}
static fs::path make_file(const fs::path& d, size_t n) {
  auto p = d / "input.bin"; std::ofstream f(p, std::ios::binary);
  for (size_t i = 0; i < n; ++i) f.put(char((i * 7 + i / 1000) & 0xff));
  return p;
}

TEST(manifest_sign_encode_decode) {
  auto d = tdir("m"); auto id = Identity::generate(); ContentStore s(d / "store");
  auto f = make_file(d, 100000);
  ContentMeta meta; meta.title = "Test Book"; meta.category = "books"; meta.keywords = "wave test";
  auto m = s.import_file(f, meta, id, kMinChunk);
  CHECK(m.content_id == sha256_file(f));
  CHECK_EQ(m.chunk_count(), 7u);  // ceil(100000/16384)
  CHECK_EQ(m.chunk_len(6), uint32_t(100000 - 6 * 16384));
  auto m2 = Manifest::decode(m.encode());
  CHECK(m2.content_id == m.content_id); CHECK_EQ(m2.meta.title, std::string("Test Book"));
  CHECK(m2.publisher == id.public_key());
  auto enc = m.encode(); enc[40] ^= 1;
  CHECK_THROWS(Manifest::decode(enc));
  // re-signing a modified manifest by an attacker changes publisher key
  Manifest forged = m; forged.meta.title = "Evil"; CHECK_THROWS(Manifest::decode(forged.encode()));
  fs::remove_all(d);
}

TEST(manifest_rejects_unsafe_names_and_counts) {
  auto id = Identity::generate();
  Manifest m; m.size = 10; m.chunk_size = kMinChunk; m.chunks.resize(1); m.file_name = "../etc/passwd";
  CHECK_THROWS(validate_manifest_structure(m));
  m.file_name = "ok.txt"; m.chunks.resize(2);
  CHECK_THROWS(validate_manifest_structure(m));
  m.chunks.resize(1); m.sign(id);
  CHECK(Manifest::decode(m.encode()).size == 10);
}

TEST(store_partial_resume_and_integrity) {
  auto d = tdir("p"); auto id = Identity::generate();
  ContentStore src(d / "src"), dst(d / "dst");
  auto f = make_file(d, 200000);
  auto m = src.import_file(f, {}, id, kMinChunk);
  auto have = dst.open_partial(m);
  for (bool b : have) CHECK(!b);
  // write first half of chunks
  for (uint32_t i = 0; i < m.chunk_count() / 2; ++i) CHECK(dst.write_chunk(m, i, src.read_chunk(m, i)));
  // corrupted chunk rejected
  auto bad = src.read_chunk(m, 8); bad[0] ^= 0xff;
  CHECK(!dst.write_chunk(m, 8, bad));
  // "restart": reopen and check verified chunks survive
  ContentStore dst2(d / "dst");
  have = dst2.open_partial(m);
  for (uint32_t i = 0; i < m.chunk_count(); ++i) CHECK_EQ(bool(have[i]), i < m.chunk_count() / 2);
  CHECK(!dst2.finalize(m));  // incomplete -> hash mismatch
  for (uint32_t i = 0; i < m.chunk_count(); ++i) if (!have[i]) CHECK(dst2.write_chunk(m, i, src.read_chunk(m, i)));
  CHECK(dst2.finalize(m));
  CHECK(dst2.has_complete(m.content_id));
  CHECK(sha256_file(dst2.blob_path(m.content_id)) == sha256_file(f));
  fs::remove_all(d);
}

TEST(store_empty_file) {
  auto d = tdir("e"); auto id = Identity::generate(); ContentStore s(d / "s");
  auto f = make_file(d, 0);
  auto m = s.import_file(f, {}, id);
  CHECK_EQ(m.chunk_count(), 0u);
  CHECK(Manifest::decode(m.encode()).size == 0);
  fs::remove_all(d);
}
