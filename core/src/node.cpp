#include "wavenet/node.hpp"
#include <algorithm>
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
NodeServer::NodeServer(NodeContext& ctx, const std::string& host, uint16_t port, ServerLimits lim)
    : ctx_(ctx), listener_(host, port), lim_(lim), handshake_limiter_(lim.handshake_burst, lim.handshakes_per_sec) {}
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
    // Cheap checks BEFORE any TLS handshake or signature work (resource-exhaustion defence).
    const std::string ip = link->remote_ip();
    if (!handshake_limiter_.allow(ip, np::now_ms())) { ++refused_rate_; link->close(); continue; }
    auto pit = per_ip_.find(ip);
    if (active_ >= lim_.max_conns || (pit != per_ip_.end() && pit->second >= lim_.max_conns_per_ip)) {
      ++refused_cap_; link->close(); continue;
    }
    ++active_;
    ++per_ip_[ip];
    auto conn = std::make_shared<Conn>();
    conn->ip = ip;
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
      if (--per_ip_[conn->ip] == 0) per_ip_.erase(conn->ip);
      --active_;
    });
  }
}

void NodeServer::serve(std::shared_ptr<Session> s) {
  RateLimiter req_limit(lim_.requests_per_sec * 2, lim_.requests_per_sec, 1);
  while (running_) {
    np::Frame f;
    try { f = s->recv(60000); } catch (...) { return; }  // idle timeout / bad frame / closed
    auto err = [&](const std::string& m) { s->send(MsgType::ERROR, as_view(m), f.h.request_id); };
    if (!req_limit.allow("s", np::now_ms())) {
      ++req_limited_;
      try { err("rate limited"); } catch (...) {}
      return;  // abusive session is closed
    }
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

uint64_t PeerClient::request_chunk(const Hash256& cid, uint32_t idx) {
  uint64_t req = next_req_++;
  s_->send(MsgType::CHUNK_REQUEST, wire::chunk_request(cid, idx), req);
  return req;
}

Bytes PeerClient::receive_chunk(const Hash256& cid, uint32_t idx, uint64_t req) {
  auto f = s_->recv(timeout_);
  if (f.h.request_id != req) throw SessionError("response id mismatch");
  if (f.h.type == MsgType::ERROR) throw SessionError("peer error: " + std::string(f.payload.begin(), f.payload.end()));
  if (f.h.type != MsgType::DATA_CHUNK) throw SessionError("unexpected response type");
  Reader r(f.payload, np::kMaxPayload);
  auto c = r.fixed<32>(); uint32_t i = r.u32(); auto d = r.blob(); r.expect_done();
  if (c != cid || i != idx) throw SessionError("chunk response mismatch");
  return d;
}

// ---------------- downloader ----------------
DownloadStats download(NodeContext& ctx, const Hash256& cid, const std::vector<PeerAddr>& peers, ProgressFn progress,
                       DownloadOptions opt) {
  DownloadStats st;
  const uint32_t window = std::clamp<uint32_t>(opt.window, 1, 64);
  st.window = window;
  auto t0 = std::chrono::steady_clock::now();
  auto elapsed = [&] { return std::chrono::duration<double>(std::chrono::steady_clock::now() - t0).count(); };

  std::vector<std::unique_ptr<PeerClient>> clients;
  std::optional<Manifest> man;
  if (auto local = ctx.catalog.get(cid); local && local->state != LocalState::Remote && ctx.store.has_complete(cid))
    man = local->manifest;
  // Connect to all sources in parallel (each connect = TCP + TLS 1.3 + WAVE-NP handshake round trips).
  {
    std::vector<std::unique_ptr<PeerClient>> slots(peers.size());
    std::vector<std::string> errs(peers.size());
    std::vector<std::thread> cts;
    for (size_t i = 0; i < peers.size(); ++i)
      cts.emplace_back([&, i] {
        try { slots[i] = std::make_unique<PeerClient>(peers[i], ctx); }
        catch (const std::exception& e) { errs[i] = std::string("peer ") + peers[i].host + ":" + std::to_string(peers[i].port) + ": " + e.what(); }
      });
    for (auto& t : cts) t.join();
    for (size_t i = 0; i < peers.size(); ++i) {
      if (!slots[i]) { st.error = errs[i]; continue; }
      if (!man) {
        try { man = slots[i]->manifest(cid); }
        catch (const std::exception& e) { st.error = std::string("peer ") + peers[i].host + ": " + e.what(); continue; }
      }
      clients.push_back(std::move(slots[i]));
    }
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
    std::deque<std::pair<uint32_t, uint64_t>> inflight;  // (chunk index, request id), in send order
    auto requeue_all = [&] { std::lock_guard lk(mu); for (auto& [i, r] : inflight) todo.push_back(i); inflight.clear(); };
    for (;;) {
      // Keep up to `window` requests outstanding.
      while (inflight.size() < window) {
        uint32_t idx;
        { std::lock_guard lk(mu); if (stop || todo.empty()) break; idx = todo.front(); todo.pop_front(); }
        try { inflight.emplace_back(idx, c->request_chunk(m.content_id, idx)); }
        catch (...) { { std::lock_guard lk(mu); todo.push_back(idx); } requeue_all(); return; }  // peer gone
      }
      if (inflight.empty()) return;
      auto [idx, req] = inflight.front();
      Bytes data;
      try { data = c->receive_chunk(m.content_id, idx, req); }
      catch (...) { requeue_all(); return; }  // peer gone or misbehaving: hand its work back, retire worker
      inflight.pop_front();
      if (!ctx.store.write_chunk(m, idx, data)) {
        { std::lock_guard lk(mu); ++st.rejected; todo.push_back(idx); }
        requeue_all();
        return;  // a peer that sends one bad chunk is dropped for this download
      }
      std::lock_guard lk(mu);
      ++st.fetched; st.bytes_fetched += data.size(); ++st.per_peer[c->describe()];
      if (st.first_chunk_seconds < 0) st.first_chunk_seconds = elapsed();
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
