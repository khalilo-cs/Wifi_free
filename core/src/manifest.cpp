#include "wavenet/manifest.hpp"

namespace wavenet {

static constexpr std::string_view kDomain = "WAVE manifest v1";
static constexpr size_t kMaxText = 4096;

uint32_t Manifest::chunk_len(uint32_t i) const {
  if (i >= chunk_count()) throw std::out_of_range("chunk index");
  uint64_t off = uint64_t(i) * chunk_size;
  return uint32_t(std::min<uint64_t>(chunk_size, size - off));
}

Bytes Manifest::signed_body() const {
  Writer w;
  w.str(kDomain); w.raw(content_id); w.u64(size); w.u32(chunk_size); w.u32(chunk_count());
  for (auto& c : chunks) w.raw(c);
  w.str(meta.title); w.str(meta.description); w.str(meta.category); w.str(meta.mime);
  w.str(meta.license); w.str(meta.keywords); w.u8(uint8_t(meta.visibility));
  w.u8(meta.allow_redistribution ? 1 : 0); w.str(file_name); w.raw(publisher); w.u64(created_ms);
  return w.take();
}

Bytes Manifest::encode() const { auto b = signed_body(); b.insert(b.end(), signature.begin(), signature.end()); return b; }

void Manifest::sign(const Identity& id) { publisher = id.public_key(); signature = id.sign(signed_body()); }

void validate_manifest_structure(const Manifest& m) {
  if (m.chunk_size < kMinChunk || m.chunk_size > kMaxChunk) throw DecodeError("chunk size out of range");
  if (m.size > kMaxContentSize) throw DecodeError("content too large");
  uint64_t expect = m.size == 0 ? 0 : (m.size + m.chunk_size - 1) / m.chunk_size;
  if (m.chunks.size() != expect) throw DecodeError("chunk count mismatch");
  for (auto* s : {&m.meta.title, &m.meta.description, &m.meta.category, &m.meta.mime, &m.meta.license, &m.meta.keywords, &m.file_name})
    if (s->size() > kMaxText) throw DecodeError("text field too long");
  if (m.file_name.find('/') != std::string::npos || m.file_name.find('\\') != std::string::npos ||
      m.file_name == ".." || m.file_name == "." || m.file_name.find('\0') != std::string::npos)
    throw DecodeError("unsafe file name");
  if (uint8_t(m.meta.visibility) > 1) throw DecodeError("bad visibility");
}

Manifest Manifest::decode(ByteView b) {
  Reader r(b, kMaxText);
  Manifest m;
  if (r.str() != kDomain) throw DecodeError("not a manifest");
  m.content_id = r.fixed<32>(); m.size = r.u64(); m.chunk_size = r.u32();
  uint32_t n = r.u32();
  if (uint64_t(n) * 32 > r.remaining()) throw DecodeError("chunk list truncated");
  m.chunks.resize(n);
  for (auto& c : m.chunks) c = r.fixed<32>();
  m.meta.title = r.str(); m.meta.description = r.str(); m.meta.category = r.str(); m.meta.mime = r.str();
  m.meta.license = r.str(); m.meta.keywords = r.str(); m.meta.visibility = Visibility(r.u8());
  uint8_t redist = r.u8();
  if (redist > 1) throw DecodeError("bad flag");
  m.meta.allow_redistribution = redist == 1;
  m.file_name = r.str(); m.publisher = r.fixed<32>(); m.created_ms = r.u64();
  m.signature = r.fixed<64>();
  r.expect_done();
  validate_manifest_structure(m);
  if (!verify_signature(m.publisher, m.signed_body(), m.signature)) throw DecodeError("bad manifest signature");
  return m;
}

}  // namespace wavenet
