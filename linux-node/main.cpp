// wavenode — WAVE-NET ∞ Linux node CLI (experimental).
#include <sys/resource.h>
#include <csignal>
#include <cstdio>
#include <cstdlib>
#include <fstream>
#include <iostream>
#include <map>
#include <set>
#include <thread>
#include "wavenet/discovery.hpp"
#include "wavenet/node.hpp"

using namespace wavenet;
namespace fs = std::filesystem;

static volatile std::sig_atomic_t g_stop = 0;
static void on_signal(int) { g_stop = 1; }

struct Args {
  std::vector<std::string> pos;
  std::multimap<std::string, std::string> opt;
  bool has(const std::string& k) const { return opt.count(k) > 0; }
  std::string get(const std::string& k, const std::string& d = "") const { auto it = opt.find(k); return it == opt.end() ? d : it->second; }
  std::vector<std::string> all(const std::string& k) const {
    std::vector<std::string> v; auto [a, b] = opt.equal_range(k); for (auto i = a; i != b; ++i) v.push_back(i->second); return v;
  }
};

static const std::set<std::string> kFlags = {"--private", "--no-redistribute", "--allow-only", "--no-multicast", "--json"};

static Args parse(int argc, char** argv) {
  Args a;
  for (int i = 1; i < argc; ++i) {
    std::string s = argv[i];
    if (s.rfind("--", 0) == 0) {
      if (kFlags.count(s)) a.opt.emplace(s, "1");
      else if (i + 1 < argc) a.opt.emplace(s, argv[++i]);
      else throw std::runtime_error("missing value for " + s);
    } else a.pos.push_back(s);
  }
  return a;
}

static fs::path data_dir(const Args& a) {
  if (a.has("--data")) return a.get("--data");
  if (const char* e = std::getenv("WAVENET_HOME")) return e;
  const char* h = std::getenv("HOME");
  return fs::path(h ? h : ".") / ".wavenet";
}

static TrustPolicy load_trust(const fs::path& d) {
  TrustPolicy t;
  std::ifstream f(d / "trust.txt");
  std::string kind, id;
  while (f >> kind) {
    if (kind == "mode") { f >> id; if (id == "allowlist") t.mode = TrustPolicy::Mode::Allowlist; continue; }
    if (!(f >> id)) break;
    try { auto h = hash_from_hex(id); (kind == "allow" ? t.allow : t.block).insert(h); } catch (...) {}
  }
  return t;
}

static PeerAddr parse_peer(const std::string& s) {
  auto c = s.rfind(':');
  if (c == std::string::npos) throw std::runtime_error("peer must be host:port");
  return {s.substr(0, c), uint16_t(std::stoi(s.substr(c + 1)))};
}

static DiscoveryConfig disc_cfg(const Args& a) {
  DiscoveryConfig c;
  c.port = uint16_t(std::stoi(a.get("--disc-port", "47800")));
  c.iface_addr = a.get("--multicast-if", "0.0.0.0");
  c.multicast = !a.has("--no-multicast");
  for (auto& s : a.all("--seed")) { auto p = parse_peer(s); c.seeds.push_back({p.host, p.port}); }
  return c;
}

static std::string human(uint64_t n) {
  const char* u[] = {"B", "KiB", "MiB", "GiB"}; double v = double(n); int i = 0;
  while (v >= 1024 && i < 3) { v /= 1024; ++i; }
  char b[32]; std::snprintf(b, sizeof b, "%.1f %s", v, u[i]); return b;
}

static void print_entry(const Manifest& m, const char* state = nullptr) {
  std::printf("%s  %-30s  %10s  [%s]%s%s\n", hex(m.content_id).c_str(), m.meta.title.c_str(), human(m.size).c_str(),
              m.meta.category.empty() ? "-" : m.meta.category.c_str(),
              m.meta.visibility == Visibility::Private ? " private" : "", state ? state : "");
}

static std::vector<PeerAddr> resolve_peers(const Args& a, const Identity& id, uint32_t net) {
  std::vector<PeerAddr> ps;
  for (auto& s : a.all("--peer")) ps.push_back(parse_peer(s));
  int wait = std::stoi(a.get("--discover", ps.empty() ? "3" : "0"));
  if (wait > 0) {
    Discovery d(id, net, disc_cfg(a));
    d.poll(wait * 1000);
    for (auto& p : d.peers()) {
      std::fprintf(stderr, "discovered %s (%s) at %s:%u\n", hex(p.id).substr(0, 16).c_str(), p.ann.name.c_str(), p.ip.c_str(), p.ann.tcp_port);
      ps.push_back({p.ip, p.ann.tcp_port});
    }
  }
  return ps;
}

static void usage() {
  std::puts(
      "wavenode — WAVE-NET node (experimental)\n"
      "global: --data DIR (default $WAVENET_HOME or ~/.wavenet)  --network N (default 1)\n"
      "  id                                  create/show node identity\n"
      "  publish FILE [--title T --desc D --category C --keywords K --license L --private --no-redistribute]\n"
      "  list                                list local catalog\n"
      "  search TEXT [--category C]          search local catalog\n"
      "  unpublish CONTENT_ID                remove own item from catalog (keeps blob)\n"
      "  serve [--bind H --port P --name N --announce-ms MS] [discovery opts]\n"
      "  peers [--discover SECONDS]          list nodes announcing on the LAN\n"
      "  search-net TEXT [--peer H:P ...] [--discover S]\n"
      "  fetch CONTENT_ID [--peer H:P ...] [--discover S] [--out FILE] [--stop-after N]\n"
      "  trust allow|block NODE_ID | trust mode open|allowlist\n"
      "discovery opts: --multicast-if IP  --disc-port P  --no-multicast  --seed H:P");
}

int main(int argc, char** argv) {
  std::signal(SIGPIPE, SIG_IGN);
  try {
    Args a = parse(argc, argv);
    if (a.pos.empty()) { usage(); return 2; }
    const std::string cmd = a.pos[0];
    auto dir = data_dir(a);
    if (cmd == "trust") {
      if (a.pos.size() != 3) { usage(); return 2; }
      fs::create_directories(dir);
      if (a.pos[1] == "mode") {
        if (a.pos[2] != "open" && a.pos[2] != "allowlist") throw std::runtime_error("mode must be open|allowlist");
      } else if (a.pos[1] == "allow" || a.pos[1] == "block") {
        hash_from_hex(a.pos[2]);  // validate
      } else { usage(); return 2; }
      std::ofstream(dir / "trust.txt", std::ios::app) << a.pos[1] << " " << a.pos[2] << "\n";
      std::puts("ok");
      return 0;
    }
    auto id = Identity::load_or_create(dir);
    uint32_t net = uint32_t(std::stoul(a.get("--network", "1")));
    if (cmd == "id") { std::printf("%s\n", hex(id.node_id()).c_str()); return 0; }

    Catalog cat(dir / "catalog.db");
    ContentStore store(dir / "store");
    NodeContext ctx{id, cat, store, net, load_trust(dir)};

    if (cmd == "publish") {
      if (a.pos.size() != 2) { usage(); return 2; }
      ContentMeta m;
      m.title = a.get("--title"); m.description = a.get("--desc"); m.category = a.get("--category");
      m.keywords = a.get("--keywords"); m.license = a.get("--license");
      m.visibility = a.has("--private") ? Visibility::Private : Visibility::Public;
      m.allow_redistribution = !a.has("--no-redistribute");
      auto man = store.import_file(a.pos[1], m, id);
      cat.put(man, LocalState::Published);
      std::printf("%s\n", hex(man.content_id).c_str());
      std::fprintf(stderr, "published %s (%s, %u chunks)\n", man.meta.title.c_str(), human(man.size).c_str(), man.chunk_count());
      return 0;
    }
    if (cmd == "list" || cmd == "search") {
      SearchQuery q; q.text = cmd == "search" && a.pos.size() > 1 ? a.pos[1] : ""; q.category = a.get("--category"); q.limit = 200;
      const char* names[] = {" (published)", " (downloaded)", " (remote)"};
      for (auto& e : cat.search(q)) print_entry(e.manifest, names[int(e.state)]);
      return 0;
    }
    if (cmd == "unpublish") {
      if (a.pos.size() != 2) { usage(); return 2; }
      bool ok = cat.remove(hash_from_hex(a.pos[1]), id.public_key());
      std::puts(ok ? "removed" : "not found (only own items can be unpublished)");
      return ok ? 0 : 1;
    }
    if (cmd == "serve") {
      std::signal(SIGINT, on_signal); std::signal(SIGTERM, on_signal);
      NodeServer srv(ctx, a.get("--bind", "0.0.0.0"), uint16_t(std::stoi(a.get("--port", "47801"))));
      srv.start();
      Discovery disc(id, net, disc_cfg(a));
      Announce ann; ann.tcp_port = srv.port(); ann.name = a.get("--name", "wavenode"); ann.roles = ROLE_PUBLISHER | ROLE_HOST;
      std::printf("node %s serving on port %u (network %u)\n", hex(id.node_id()).c_str(), srv.port(), net);
      std::fflush(stdout);
      int every = std::stoi(a.get("--announce-ms", "1000"));
      uint64_t next = 0;
      while (!g_stop) {
        if (np::now_ms() >= next) {
          ann.content_count = uint32_t(cat.count());
          disc.announce(ann);
          next = np::now_ms() + uint64_t(every);
        }
        disc.poll(200);
      }
      srv.stop();
      std::printf("stopped (chunks served: %llu)\n", (unsigned long long)srv.chunks_served());
      return 0;
    }
    if (cmd == "peers") {
      Discovery d(id, net, disc_cfg(a));
      d.poll(std::stoi(a.get("--discover", "3")) * 1000);
      for (auto& p : d.peers())
        std::printf("%s  %-16s %s:%u  items=%u\n", hex(p.id).c_str(), p.ann.name.c_str(), p.ip.c_str(), p.ann.tcp_port, p.ann.content_count);
      return 0;
    }
    if (cmd == "search-net") {
      SearchQuery q; q.text = a.pos.size() > 1 ? a.pos[1] : ""; q.category = a.get("--category");
      int found = 0;
      for (auto& p : resolve_peers(a, id, net)) {
        try {
          PeerClient c(p, ctx);
          for (auto& m : c.search(q)) { cat.put(m, LocalState::Remote); print_entry(m, (" @ " + p.host + ":" + std::to_string(p.port)).c_str()); ++found; }
        } catch (const std::exception& e) { std::fprintf(stderr, "peer %s:%u: %s\n", p.host.c_str(), p.port, e.what()); }
      }
      std::fprintf(stderr, "%d result(s)\n", found);
      return 0;
    }
    if (cmd == "fetch") {
      if (a.pos.size() != 2) { usage(); return 2; }
      auto cid = hash_from_hex(a.pos[1]);
      auto peers = resolve_peers(a, id, net);
      long stop_after = std::stol(a.get("--stop-after", "-1"));
      uint32_t last_pct = 101;
      auto st = download(ctx, cid, peers, [&](uint32_t done, uint32_t total) {
        uint32_t pct = total ? done * 100 / total : 100;
        if (pct != last_pct && pct % 10 == 0) { std::fprintf(stderr, "  %u/%u chunks (%u%%)\n", done, total, pct); last_pct = pct; }
        return stop_after < 0 || long(done) < stop_after;
      });
      double mbps = st.seconds > 0 ? double(st.bytes_fetched) / st.seconds / (1024 * 1024) : 0;
      std::fprintf(stderr, "chunks: total=%u already_had=%u fetched=%u rejected=%u  bytes=%s  time=%.3fs  (%.1f MiB/s measured)\n",
                   st.total_chunks, st.already_had, st.fetched, st.rejected, human(st.bytes_fetched).c_str(), st.seconds, mbps);
      for (auto& [peer, n] : st.per_peer) std::fprintf(stderr, "  source %s: %u chunks\n", peer.c_str(), n);
      rusage ru{};
      if (::getrusage(RUSAGE_SELF, &ru) == 0) {
        double cpu = double(ru.ru_utime.tv_sec + ru.ru_stime.tv_sec) + double(ru.ru_utime.tv_usec + ru.ru_stime.tv_usec) / 1e6;
        std::fprintf(stderr, "resources: peak_rss=%ld KiB  cpu=%.3fs\n", ru.ru_maxrss, cpu);
      }
      if (!st.complete) { std::fprintf(stderr, "INCOMPLETE: %s (run again to resume)\n", st.error.c_str()); return 3; }
      std::printf("verified %s\n", hex(cid).c_str());
      if (a.has("--out")) { store.export_to(cid, a.get("--out")); std::fprintf(stderr, "saved to %s\n", a.get("--out").c_str()); }
      return 0;
    }
    usage();
    return 2;
  } catch (const std::exception& e) {
    std::fprintf(stderr, "error: %s\n", e.what());
    return 1;
  }
}
