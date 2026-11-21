#include "wavenet/node.hpp"
#include <chrono>
#include <deque>

namespace wavenet {
using np::MsgType;

namespace wire {
Bytes search_request(const SearchQuery& q) {
  Writer w; w.str(q.text.substr(0, 256)); w.str(q.category.substr(0, 64)); w.u32(q.limit); w.u32(q.offset); return w.take();
}
SearchQuery parse_search_request(ByteView b) {
  Reader r(b, 256); SearchQuery q; q.text = r.str(); q.category = r.str(); q.limit = std::min<uint32_t>(r.u32(), 50);
  q.offset = r.u32(); r.expect_done(); q.public_only = true; return q;
}
Bytes search_response(const std::vector<Manifest>& ms) {
  Writer body; uint32_t n = 0;
  for (auto& m : ms) {
    auto e = m.encode();
    if (body.data().size() + e.size() + 8 > np::kMaxPayload - 1024) break;
    body.blob(e); ++n;
  }
  Writer w; w.u32(n); w.raw(body.data()); return w.take();
}
std::vector<Manifest> parse_search_response(ByteView b) {
  Reader r(b, np::kMaxPayload); uint32_t n = r.u32();
  if (n > 200) throw DecodeError("too many results");
  std::vector<Manifest> out;
  for (uint32_t i = 0; i < n; ++i) out.push_back(Manifest::decode(r.blob()));
  r.expect_done(); return out;
}
Bytes chunk_request(const Hash256& cid, uint32_t idx) { Writer w; w.raw(cid); w.u32(idx); return w.take(); }
Bytes data_chunk(const Hash256& cid, uint32_t idx, ByteView d) { Writer w; w.raw(cid); w.u32(idx); w.blob(d); return w.take(); }
}  // namespace wire

bool servable(const NodeContext& ctx, const CatalogEntry& e) {
  const auto& m = e.manifest;
  if (m.meta.visibility != Visibility::Public) return false;
  if (e.state == LocalState::Remote) return false;
  if (m.publisher != ctx.id.public_key() && !m.meta.allow_redistribution) return false;
  return ctx.store.has_complete(m.content_id);
}

// ---------------- server ----------------
NodeServer::NodeServer(NodeContext& ctx, const std::string& host, uint16_t port, size_t max_conns)
    : ctx_(ctx), listener_(host, port), max_conns_(max_conns) {}
NodeServer::~NodeServer() { stop(); }

void NodeServer::start() {
  running_ = true;
  accept_thread_ = std::thread([this] { accept_loop(); });
}

void NodeServer::stop() {
  if (!running_.exchange(false)) return;
  if (accept_thread_.joinable()) accept_thread_.join();
  listener_.close();
  std::list<std::shared_ptr<Conn>> conns;
  {
    std::lock_guard lk(mu_);
    conns.swap(conns_);
    for (auto& c : conns) if (c->s) c->s->close();  // unblocks recv() in serving threads
  }
  for (auto& c : conns) if (c->t.joinable()) c->t.join();
}

void NodeServer::accept_loop() {
  while (running_) {
    std::unique_ptr<Link> link;
    try { link = listener_.accept(200); } catch (...) { break; }
    std::lock_guard lk(mu_);
    for (auto it = conns_.begin(); it != conns_.end();) {  // reap finished connections
      if ((*it)->done) { if ((*it)->t.joinable()) (*it)->t.join(); it = conns_.erase(it); } else ++it;
    }
    if (!link) continue;
    if (active_ >= max_conns_) { link->close(); continue; }  // resource limit
    ++active_;
    auto conn = std::make_shared<Conn>();
    conns_.push_back(conn);
    Link* raw = link.release();
    conn->t = std::thread([this, raw, conn] {
      std::shared_ptr<Session> s;
      try {
        std::unique_ptr<Link> l(raw);
        if (ctx_.tls) l = tls_wrap(std::move(l), TlsRole::Server);
        s = std::shared_ptr<Session>(Session::respond(std::move(l), ctx_.id, ctx_.network_id, ctx_.trust));
      } catch (...) {}
      if (s) {
        { std::lock_guard l2(mu_); conn->s = s; if (!running_) s->close(); }
        serve(s);
        s->close();
      }
      std::lock_guard l2(mu_);
      conn->s.reset();
      conn->done = true;
      --active_;
    });
  }
}

void NodeServer::serve(std::shared_ptr<Session> s) {
  while (running_) {
    np::Frame f;
    try { f = s->recv(60000); } catch (...) { return; }  // idle timeout / bad frame / closed
    auto err = [&](const std::string& m) { s->send(MsgType::ERROR, as_view(m), f.h.request_id); };
    try {
      switch (f.h.type) {
        case MsgType::SEARCH_REQUEST: {
          auto q = wire::parse_search_request(f.payload);
          std::vector<Manifest> ms;
          for (auto& e : ctx_.catalog.search(q)) if (servable(ctx_, e)) ms.push_back(e.manifest);
          s->send(MsgType::SEARCH_RESPONSE, wire::search_response(ms), f.h.request_id);
          break;
        }
        case MsgType::MANIFEST_REQUEST: {
          Reader r(f.payload); auto cid = r.fixed<32>(); r.expect_done();
          auto e = ctx_.catalog.get(cid);
          Writer w;
          if (e && servable(ctx_, *e)) { w.u8(1); w.blob(e->manifest.encode()); } else w.u8(0);
          s->send(MsgType::MANIFEST_RESPONSE, w.data(), f.h.request_id);
          break;
        }
        case MsgType::CHUNK_REQUEST: {
          Reader r(f.payload); auto cid = r.fixed<32>(); uint32_t idx = r.u32(); r.expect_done();
          auto e = ctx_.catalog.get(cid);
          if (!e || !servable(ctx_, *e) || idx >= e->manifest.chunk_count()) { err("not available"); break; }
          auto data = ctx_.store.read_chunk(e->manifest, idx);
          s->send(MsgType::DATA_CHUNK, wire::data_chunk(cid, idx, data), f.h.request_id);
          ++chunks_served_;
          break;
        }
        default: err(std::string("unsupported message ") + np::type_name(f.h.type));
      }
    } catch (const DecodeError&) {
      try { err("malformed request"); } catch (...) { return; }
    } catch (const TransportError&) { return; }
    catch (const std::exception&) { try { err("internal error"); } catch (...) { return; } }
  }
}

// ---------------- client ----------------
PeerClient::PeerClient(const PeerAddr& a, NodeContext& ctx, int timeout_ms) : timeout_(timeout_ms) {
  auto l = tcp_connect(a.host, a.port, timeout_ms);
  if (ctx.tls) l = tls_wrap(std::move(l), TlsRole::Client, timeout_ms);
  s_ = Session::initiate(std::move(l), ctx.id, ctx.network_id, ctx.trust);
}

np::Frame PeerClient::call(MsgType t, ByteView payload, MsgType expect) {
  uint64_t req = next_req_++;
  s_->send(t, payload, req);
  auto f = s_->recv(timeout_);
  if (f.h.request_id != req) throw SessionError("response id mismatch");
  if (f.h.type == MsgType::ERROR) throw SessionError("peer error: " + std::string(f.payload.begin(), f.payload.end()));
  if (f.h.type != expect) throw SessionError("unexpected response type");
  return f;
}

std::vector<Manifest> PeerClient::search(const SearchQuery& q) {
  return wire::parse_search_response(call(MsgType::SEARCH_REQUEST, wire::search_request(q), MsgType::SEARCH_RESPONSE).payload);
}

std::optional<Manifest> PeerClient::manifest(const Hash256& cid) {
  auto f = call(MsgType::MANIFEST_REQUEST, cid, MsgType::MANIFEST_RESPONSE);
  Reader r(f.payload, np::kMaxPayload);
  if (r.u8() == 0) return std::nullopt;
  auto m = Manifest::decode(r.blob());
  r.expect_done();
  if (m.content_id != cid) throw SessionError("peer returned manifest for different content");
  return m;
}

Bytes PeerClient::chunk(const Hash256& cid, uint32_t idx) {
  auto f = call(MsgType::CHUNK_REQUEST, wire::chunk_request(cid, idx), MsgType::DATA_CHUNK);
  Reader r(f.payload, np::kMaxPayload);
  auto c = r.fixed<32>(); uint32_t i = r.u32(); auto d = r.blob(); r.expect_done();
  if (c != cid || i != idx) throw SessionError("chunk response mismatch");
  return d;
}

// ---------------- downloader ----------------
DownloadStats download(NodeContext& ctx, const Hash256& cid, const std::vector<PeerAddr>& peers, ProgressFn progress) {
  DownloadStats st;
  auto t0 = std::chrono::steady_clock::now();
  auto elapsed = [&] { return std::chrono::duration<double>(std::chrono::steady_clock::now() - t0).count(); };

  std::vector<std::unique_ptr<PeerClient>> clients;
  std::optional<Manifest> man;
  if (auto local = ctx.catalog.get(cid); local && local->state != LocalState::Remote && ctx.store.has_complete(cid))
    man = local->manifest;
  for (auto& p : peers) {
    try {
      auto c = std::make_unique<PeerClient>(p, ctx);
      if (!man) man = c->manifest(cid);
      clients.push_back(std::move(c));
    } catch (const std::exception& e) { st.error = std::string("peer ") + p.host + ":" + std::to_string(p.port) + ": " + e.what(); }
  }
  if (!man) { st.error = st.error.empty() ? "content not found on any peer" : st.error; st.seconds = elapsed(); return st; }
  const Manifest m = *man;
  ctx.catalog.put(m, LocalState::Remote);
  st.total_chunks = m.chunk_count();

  auto have = ctx.store.open_partial(m);
  std::deque<uint32_t> todo;
  for (uint32_t i = 0; i < have.size(); ++i) { if (have[i]) ++st.already_had; else todo.push_back(i); }

  std::mutex mu;
  std::atomic<bool> stop{false};
  auto worker = [&](PeerClient* c) {
    uint32_t strikes = 0;
    for (;;) {
      uint32_t idx;
      { std::lock_guard lk(mu); if (stop || todo.empty()) return; idx = todo.front(); todo.pop_front(); }
      Bytes data;
      try { data = c->chunk(m.content_id, idx); }
      catch (...) { std::lock_guard lk(mu); todo.push_back(idx); return; }  // peer gone: requeue, retire worker
      if (!ctx.store.write_chunk(m, idx, data)) {
        std::lock_guard lk(mu); ++st.rejected; todo.push_back(idx);
        if (++strikes >= 1) return;  // a peer that sends one bad chunk is dropped for this download
        continue;
      }
      std::lock_guard lk(mu);
      ++st.fetched; st.bytes_fetched += data.size(); ++st.per_peer[c->describe()];
      if (progress && !progress(st.fetched + st.already_had, st.total_chunks)) stop = true;
    }
  };
  std::vector<std::thread> ts;
  for (auto& c : clients) ts.emplace_back(worker, c.get());
  for (auto& t : ts) t.join();

  st.seconds = elapsed();
  if (!todo.empty()) {
    if (st.error.empty()) st.error = stop ? "stopped by caller" : "no remaining peer could supply all chunks";
    return st;  // partial file kept; next call resumes
  }
  if (!ctx.store.finalize(m)) { st.error = "final SHA-256 mismatch"; return st; }
  ctx.catalog.put(m, LocalState::Downloaded);
  st.complete = true;
  st.error.clear();
  return st;
}

}  // namespace wavenet
