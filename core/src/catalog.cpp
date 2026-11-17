#include "wavenet/catalog.hpp"
#include <sqlite3.h>
#include <sstream>

namespace wavenet {

namespace {
struct Stmt {
  sqlite3_stmt* s = nullptr;
  Stmt(sqlite3* db, const char* sql) {
    if (sqlite3_prepare_v2(db, sql, -1, &s, nullptr) != SQLITE_OK) throw std::runtime_error(sqlite3_errmsg(db));
  }
  ~Stmt() { sqlite3_finalize(s); }
  void text(int i, const std::string& v) { sqlite3_bind_text(s, i, v.data(), int(v.size()), SQLITE_TRANSIENT); }
  void blob(int i, ByteView v) { sqlite3_bind_blob(s, i, v.data(), int(v.size()), SQLITE_TRANSIENT); }
  void i64(int i, int64_t v) { sqlite3_bind_int64(s, i, v); }
};

void exec(sqlite3* db, const char* sql) {
  char* err = nullptr;
  if (sqlite3_exec(db, sql, nullptr, nullptr, &err) != SQLITE_OK) {
    std::string e = err ? err : "sqlite error"; sqlite3_free(err); throw std::runtime_error(e);
  }
}

std::string like_escape(const std::string& t) {
  std::string o; o.reserve(t.size() + 2); o.push_back('%');
  for (char c : t) { if (c == '%' || c == '_' || c == '\\') o.push_back('\\'); o.push_back(c); }
  o.push_back('%'); return o;
}

CatalogEntry row_entry(sqlite3_stmt* s) {
  auto* p = static_cast<const uint8_t*>(sqlite3_column_blob(s, 0));
  int n = sqlite3_column_bytes(s, 0);
  CatalogEntry e{Manifest::decode(ByteView(p, size_t(n))), LocalState(sqlite3_column_int(s, 1))};
  return e;
}
}  // namespace

Catalog::Catalog(const std::filesystem::path& path) {
  if (sqlite3_open_v2(path.c_str(), &db_, SQLITE_OPEN_READWRITE | SQLITE_OPEN_CREATE | SQLITE_OPEN_FULLMUTEX, nullptr) != SQLITE_OK) {
    std::string e = db_ ? sqlite3_errmsg(db_) : "open failed"; sqlite3_close(db_); throw std::runtime_error(e);
  }
  exec(db_, "PRAGMA journal_mode=WAL; PRAGMA foreign_keys=ON;"
            "CREATE TABLE IF NOT EXISTS content ("
            " cid BLOB NOT NULL, publisher BLOB NOT NULL, title TEXT, description TEXT, category TEXT,"
            " keywords TEXT, file_name TEXT, size INTEGER, visibility INTEGER, created_ms INTEGER,"
            " state INTEGER NOT NULL, manifest BLOB NOT NULL, PRIMARY KEY (cid, publisher));"
            "CREATE INDEX IF NOT EXISTS content_category ON content(category);"
            "PRAGMA user_version=1;");
}

Catalog::~Catalog() { sqlite3_close(db_); }

void Catalog::put(const Manifest& m, LocalState st) {
  std::lock_guard lk(mu_);
  Stmt q(db_, "INSERT INTO content(cid,publisher,title,description,category,keywords,file_name,size,visibility,created_ms,state,manifest)"
              " VALUES(?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(cid,publisher) DO UPDATE SET"
              " title=excluded.title, description=excluded.description, category=excluded.category,"
              " keywords=excluded.keywords, file_name=excluded.file_name, visibility=excluded.visibility,"
              " created_ms=excluded.created_ms, manifest=excluded.manifest,"
              " state=MIN(content.state, excluded.state)");
  q.blob(1, m.content_id); q.blob(2, m.publisher); q.text(3, m.meta.title); q.text(4, m.meta.description);
  q.text(5, m.meta.category); q.text(6, m.meta.keywords); q.text(7, m.file_name); q.i64(8, int64_t(m.size));
  q.i64(9, int64_t(m.meta.visibility)); q.i64(10, int64_t(m.created_ms)); q.i64(11, int64_t(st));
  auto enc = m.encode(); q.blob(12, enc);
  if (sqlite3_step(q.s) != SQLITE_DONE) throw std::runtime_error(sqlite3_errmsg(db_));
}

bool Catalog::remove(const Hash256& cid, const PublicKey& pub) {
  std::lock_guard lk(mu_);
  Stmt q(db_, "DELETE FROM content WHERE cid=? AND publisher=?");
  q.blob(1, cid); q.blob(2, pub);
  if (sqlite3_step(q.s) != SQLITE_DONE) throw std::runtime_error(sqlite3_errmsg(db_));
  return sqlite3_changes(db_) > 0;
}

std::optional<CatalogEntry> Catalog::get(const Hash256& cid) const {
  std::lock_guard lk(mu_);
  Stmt q(db_, "SELECT manifest, state FROM content WHERE cid=? ORDER BY state ASC LIMIT 1");
  q.blob(1, cid);
  if (sqlite3_step(q.s) == SQLITE_ROW) return row_entry(q.s);
  return std::nullopt;
}

std::vector<CatalogEntry> Catalog::search(const SearchQuery& sq) const {
  std::vector<std::string> terms;
  { std::istringstream is(sq.text); std::string t; while (is >> t && terms.size() < 8) terms.push_back(t); }
  std::string sql = "SELECT manifest, state FROM content WHERE 1=1";
  for (size_t i = 0; i < terms.size(); ++i)
    sql += " AND (title LIKE ? ESCAPE '\\' OR description LIKE ? ESCAPE '\\' OR keywords LIKE ? ESCAPE '\\' OR file_name LIKE ? ESCAPE '\\')";
  if (!sq.category.empty()) sql += " AND category = ?";
  // Remote peers only see public items this node actually holds (published or downloaded).
  if (sq.public_only) sql += " AND visibility = 0 AND state IN (0,1)";
  sql += " ORDER BY created_ms DESC LIMIT ? OFFSET ?";
  std::lock_guard lk(mu_);
  Stmt q(db_, sql.c_str());
  int i = 1;
  for (auto& t : terms) { auto p = like_escape(t); for (int k = 0; k < 4; ++k) q.text(i++, p); }
  if (!sq.category.empty()) q.text(i++, sq.category);
  q.i64(i++, std::min<uint32_t>(sq.limit, 200)); q.i64(i++, sq.offset);
  std::vector<CatalogEntry> out;
  int rc;
  while ((rc = sqlite3_step(q.s)) == SQLITE_ROW) out.push_back(row_entry(q.s));
  if (rc != SQLITE_DONE) throw std::runtime_error(sqlite3_errmsg(db_));
  return out;
}

size_t Catalog::count() const {
  std::lock_guard lk(mu_);
  Stmt q(db_, "SELECT COUNT(*) FROM content");
  sqlite3_step(q.s);
  return size_t(sqlite3_column_int64(q.s, 0));
}

}  // namespace wavenet
