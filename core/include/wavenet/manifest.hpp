// Signed content manifest (WAVE Data Fabric v0).
#pragma once
#include <string>
#include <vector>
#include "wavenet/identity.hpp"

namespace wavenet {

constexpr uint32_t kMinChunk = 16u << 10;
constexpr uint32_t kMaxChunk = 1u << 20;
constexpr uint32_t kDefaultChunk = 256u << 10;
constexpr uint64_t kMaxContentSize = 64ull << 30;  // 64 GiB per item

enum class Visibility : uint8_t { Public = 0, Private = 1 };

struct ContentMeta {
  std::string title, description, category, mime, license, keywords;  // keywords: space-separated
  Visibility visibility = Visibility::Public;
  bool allow_redistribution = true;
};

struct Manifest {
  Hash256 content_id{};          // SHA-256 of full content
  uint64_t size = 0;
  uint32_t chunk_size = kDefaultChunk;
  std::vector<Hash256> chunks;   // SHA-256 of each chunk
  ContentMeta meta;
  std::string file_name;
  PublicKey publisher{};
  uint64_t created_ms = 0;
  Signature signature{};

  uint32_t chunk_count() const { return uint32_t(chunks.size()); }
  uint32_t chunk_len(uint32_t i) const;
  Bytes signed_body() const;     // canonical encoding without signature
  Bytes encode() const;          // body + signature
  static Manifest decode(ByteView b);  // validates structure AND signature; throws DecodeError
  void sign(const Identity& id);
};

// Validates sizes/limits; throws DecodeError.
void validate_manifest_structure(const Manifest& m);

}  // namespace wavenet
