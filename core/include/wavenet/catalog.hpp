// Local content catalog (SQLite). Keys: (content_id, publisher).
#pragma once
#include <filesystem>
#include <mutex>
#include <optional>
#include "wavenet/manifest.hpp"

struct sqlite3;

namespace wavenet {

enum class LocalState : uint8_t { Published = 0, Downloaded = 1, Remote = 2 };

struct SearchQuery {
  std::string text;          // space-separated terms, all must match (title/description/keywords/file name)
  std::string category;      // optional exact match
  bool public_only = false;  // true for queries answered on behalf of remote peers
  uint32_t limit = 50, offset = 0;
};

struct CatalogEntry {
  Manifest manifest;
  LocalState state{};
};

class Catalog {
 public:
  explicit Catalog(const std::filesystem::path& db_path);
  ~Catalog();
  Catalog(const Catalog&) = delete;
  Catalog& operator=(const Catalog&) = delete;

  void put(const Manifest& m, LocalState st);  // upsert
  bool remove(const Hash256& cid, const PublicKey& publisher);
  std::optional<CatalogEntry> get(const Hash256& cid) const;  // any publisher, prefers local
  std::vector<CatalogEntry> search(const SearchQuery& q) const;
  size_t count() const;

 private:
  sqlite3* db_ = nullptr;
  mutable std::mutex mu_;
};

}  // namespace wavenet
