// Content-addressed local store: complete blobs + resumable partial downloads.
#pragma once
#include <filesystem>
#include <mutex>
#include <optional>
#include "wavenet/manifest.hpp"

namespace wavenet {

class ContentStore {
 public:
  explicit ContentStore(std::filesystem::path root);

  // Hashes + chunks `file`, copies it into the store, returns a signed manifest.
  Manifest import_file(const std::filesystem::path& file, ContentMeta meta, const Identity& publisher,
                       uint32_t chunk_size = kDefaultChunk);

  bool has_complete(const Hash256& cid) const;
  Bytes read_chunk(const Manifest& m, uint32_t idx) const;  // throws if missing
  std::filesystem::path blob_path(const Hash256& cid) const;

  // --- resumable download ---
  // Returns per-chunk "already verified" flags by re-hashing whatever is in the .part file.
  std::vector<bool> open_partial(const Manifest& m);
  // Verifies the chunk hash, then writes it. Returns false (and writes nothing) on mismatch.
  bool write_chunk(const Manifest& m, uint32_t idx, ByteView data);
  // Verifies the whole-file SHA-256 and moves .part into the blob store. Returns false on mismatch.
  bool finalize(const Manifest& m);
  std::filesystem::path partial_path(const Hash256& cid) const;

  // Copies a complete blob to `dest` (export to user).
  void export_to(const Hash256& cid, const std::filesystem::path& dest) const;

 private:
  std::filesystem::path root_;
  mutable std::mutex mu_;
};

}  // namespace wavenet
