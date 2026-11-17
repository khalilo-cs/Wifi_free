#include "wavenet/store.hpp"
#include <fcntl.h>
#include <unistd.h>
#include <fstream>
#include "wavenet/protocol.hpp"

namespace wavenet {
namespace fs = std::filesystem;

ContentStore::ContentStore(fs::path root) : root_(std::move(root)) {
  fs::create_directories(root_ / "blobs");
  fs::create_directories(root_ / "partial");
}

fs::path ContentStore::blob_path(const Hash256& cid) const { return root_ / "blobs" / hex(cid); }
fs::path ContentStore::partial_path(const Hash256& cid) const { return root_ / "partial" / (hex(cid) + ".part"); }
bool ContentStore::has_complete(const Hash256& cid) const { return fs::is_regular_file(blob_path(cid)); }

static bool read_at(int fd, uint8_t* buf, size_t n, uint64_t off) {
  size_t got = 0;
  while (got < n) {
    ssize_t r = ::pread(fd, buf + got, n - got, off_t(off + got));
    if (r <= 0) return false;
    got += size_t(r);
  }
  return true;
}
static bool write_at(int fd, const uint8_t* buf, size_t n, uint64_t off) {
  size_t put = 0;
  while (put < n) {
    ssize_t r = ::pwrite(fd, buf + put, n - put, off_t(off + put));
    if (r <= 0) return false;
    put += size_t(r);
  }
  return true;
}

Manifest ContentStore::import_file(const fs::path& file, ContentMeta meta, const Identity& publisher, uint32_t chunk_size) {
  Manifest m;
  m.chunk_size = chunk_size;
  m.size = fs::file_size(file);
  if (m.size > kMaxContentSize) throw std::runtime_error("file too large");
  m.meta = std::move(meta);
  m.file_name = file.filename().string();
  m.created_ms = np::now_ms();
  if (m.meta.title.empty()) m.meta.title = m.file_name;

  auto tmp = root_ / "partial" / ("import-" + std::to_string(::getpid()) + "-" + std::to_string(m.created_ms));
  std::ifstream in(file, std::ios::binary);
  std::ofstream out(tmp, std::ios::binary | std::ios::trunc);
  if (!in || !out) throw std::runtime_error("cannot open file for import");
  Sha256Stream whole;
  Bytes buf(chunk_size);
  uint64_t left = m.size;
  while (left > 0) {
    size_t n = size_t(std::min<uint64_t>(chunk_size, left));
    in.read(reinterpret_cast<char*>(buf.data()), std::streamsize(n));
    if (size_t(in.gcount()) != n) { fs::remove(tmp); throw std::runtime_error("file changed during import"); }
    ByteView v(buf.data(), n);
    m.chunks.push_back(sha256(v));
    whole.update(v);
    out.write(reinterpret_cast<const char*>(buf.data()), std::streamsize(n));
    left -= n;
  }
  out.close();
  if (!out) { fs::remove(tmp); throw std::runtime_error("write failed during import"); }
  m.content_id = whole.finish();
  validate_manifest_structure(m);
  m.sign(publisher);
  std::lock_guard lk(mu_);
  if (has_complete(m.content_id)) fs::remove(tmp); else fs::rename(tmp, blob_path(m.content_id));
  return m;
}

Bytes ContentStore::read_chunk(const Manifest& m, uint32_t idx) const {
  uint32_t len = m.chunk_len(idx);
  int fd = ::open(blob_path(m.content_id).c_str(), O_RDONLY | O_CLOEXEC);
  if (fd < 0) throw std::runtime_error("content not available locally");
  Bytes b(len);
  bool ok = read_at(fd, b.data(), len, uint64_t(idx) * m.chunk_size);
  ::close(fd);
  if (!ok) throw std::runtime_error("short read");
  return b;
}

std::vector<bool> ContentStore::open_partial(const Manifest& m) {
  std::lock_guard lk(mu_);
  std::vector<bool> have(m.chunk_count(), false);
  if (has_complete(m.content_id)) { have.assign(have.size(), true); return have; }
  auto p = partial_path(m.content_id);
  int fd = ::open(p.c_str(), O_RDWR | O_CREAT | O_CLOEXEC, 0600);
  if (fd < 0) throw std::runtime_error("cannot open partial file");
  if (::ftruncate(fd, off_t(m.size)) != 0) { ::close(fd); throw std::runtime_error("cannot size partial file"); }
  Bytes buf(m.chunk_size);
  for (uint32_t i = 0; i < m.chunk_count(); ++i) {
    uint32_t len = m.chunk_len(i);
    if (read_at(fd, buf.data(), len, uint64_t(i) * m.chunk_size) && sha256(ByteView(buf.data(), len)) == m.chunks[i])
      have[i] = true;
  }
  ::close(fd);
  return have;
}

bool ContentStore::write_chunk(const Manifest& m, uint32_t idx, ByteView data) {
  if (idx >= m.chunk_count() || data.size() != m.chunk_len(idx)) return false;
  if (sha256(data) != m.chunks[idx]) return false;
  std::lock_guard lk(mu_);
  int fd = ::open(partial_path(m.content_id).c_str(), O_WRONLY | O_CLOEXEC);
  if (fd < 0) return false;
  bool ok = write_at(fd, data.data(), data.size(), uint64_t(idx) * m.chunk_size);
  ok = (::fsync(fd) == 0) && ok;
  ::close(fd);
  return ok;
}

bool ContentStore::finalize(const Manifest& m) {
  std::lock_guard lk(mu_);
  if (has_complete(m.content_id)) return true;
  auto p = partial_path(m.content_id);
  if (sha256_file(p) != m.content_id) return false;
  fs::rename(p, blob_path(m.content_id));
  return true;
}

void ContentStore::export_to(const Hash256& cid, const fs::path& dest) const {
  fs::copy_file(blob_path(cid), dest, fs::copy_options::overwrite_existing);
}

}  // namespace wavenet
