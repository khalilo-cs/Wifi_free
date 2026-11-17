#include "wavenet/hash.hpp"
#include <fstream>
#include <openssl/evp.h>

namespace wavenet {

Sha256Stream::Sha256Stream() : ctx_(EVP_MD_CTX_new()) {
  if (!ctx_ || EVP_DigestInit_ex(static_cast<EVP_MD_CTX*>(ctx_), EVP_sha256(), nullptr) != 1)
    throw std::runtime_error("SHA-256 init failed");
}
Sha256Stream::~Sha256Stream() { EVP_MD_CTX_free(static_cast<EVP_MD_CTX*>(ctx_)); }
void Sha256Stream::update(ByteView d) {
  if (EVP_DigestUpdate(static_cast<EVP_MD_CTX*>(ctx_), d.data(), d.size()) != 1)
    throw std::runtime_error("SHA-256 update failed");
}
Hash256 Sha256Stream::finish() {
  Hash256 h{}; unsigned len = 0;
  if (EVP_DigestFinal_ex(static_cast<EVP_MD_CTX*>(ctx_), h.data(), &len) != 1 || len != 32)
    throw std::runtime_error("SHA-256 final failed");
  return h;
}

Hash256 sha256(ByteView data) { Sha256Stream s; s.update(data); return s.finish(); }

Hash256 sha256_file(const std::filesystem::path& p) {
  std::ifstream f(p, std::ios::binary);
  if (!f) throw std::runtime_error("cannot open " + p.string());
  Sha256Stream s; std::vector<char> buf(1 << 16);
  while (f) {
    f.read(buf.data(), std::streamsize(buf.size()));
    auto n = f.gcount();
    if (n > 0) s.update({reinterpret_cast<const uint8_t*>(buf.data()), size_t(n)});
  }
  return s.finish();
}

Hash256 hash_from_hex(std::string_view h) {
  auto b = from_hex(h);
  if (b.size() != 32) throw DecodeError("hash must be 32 bytes");
  Hash256 r{}; std::copy(b.begin(), b.end(), r.begin()); return r;
}

}  // namespace wavenet
