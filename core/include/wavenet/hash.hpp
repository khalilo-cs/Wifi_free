// SHA-256 via OpenSSL EVP.
#pragma once
#include <array>
#include <filesystem>
#include <string>
#include "wavenet/bytes.hpp"

namespace wavenet {

using Hash256 = std::array<uint8_t, 32>;

Hash256 sha256(ByteView data);
Hash256 sha256_file(const std::filesystem::path& p);
inline std::string hex(const Hash256& h) { return to_hex(h); }
Hash256 hash_from_hex(std::string_view hex);  // throws DecodeError

class Sha256Stream {
 public:
  Sha256Stream();
  ~Sha256Stream();
  Sha256Stream(const Sha256Stream&) = delete;
  Sha256Stream& operator=(const Sha256Stream&) = delete;
  void update(ByteView d);
  Hash256 finish();
 private:
  void* ctx_;
};

}  // namespace wavenet
