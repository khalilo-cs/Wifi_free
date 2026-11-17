# Database Schema (implemented — T5)

File: `<data_dir>/catalog.db` (SQLite, WAL). `PRAGMA user_version=1`.

```sql
CREATE TABLE content (
  cid BLOB NOT NULL,          -- SHA-256 content id (32 bytes)
  publisher BLOB NOT NULL,    -- publisher Ed25519 public key (32 bytes)
  title TEXT, description TEXT, category TEXT, keywords TEXT, file_name TEXT,
  size INTEGER, visibility INTEGER,   -- 0 public, 1 private
  created_ms INTEGER,
  state INTEGER NOT NULL,     -- 0 published here, 1 downloaded, 2 known remotely only
  manifest BLOB NOT NULL,     -- full signed manifest (re-verified on every read)
  PRIMARY KEY (cid, publisher));
CREATE INDEX content_category ON content(category);
```

Search: up to 8 terms, AND-combined, each matched with escaped `LIKE` across title/description/keywords/file name;
optional category; `LIMIT ≤ 200` + `OFFSET`. All SQL uses bound parameters.
Queries answered for remote peers (`public_only`) return only public items this node actually holds.
Upserts never downgrade the local state (published < downloaded < remote).
