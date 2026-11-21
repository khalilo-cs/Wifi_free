// WAVE Turbo transfer benchmark (single host). Latency is EMULATED by an in-process TCP relay that delays every
// forwarded byte by a fixed one-way delay. This measures protocol/engine behaviour, NOT radio performance.
#include <netinet/in.h>
#include <poll.h>
#include <sys/socket.h>
#include <unistd.h>
#include <chrono>
#include <condition_variable>
#include <cstdio>
#include <deque>
#include <fstream>
#include <thread>
#include "wavenet/node.hpp"

using namespace wavenet;
namespace fs = std::filesystem;
using Clock = std::chrono::steady_clock;

namespace {

// Forwards bytes src->dst, delivering each read block `delay_ms` after it was received.
void pump(int src, int dst, int delay_ms, std::atomic<bool>& stop) {
  struct Item { Clock::time_point due; Bytes data; };
  std::deque<Item> q; std::mutex mu; std::condition_variable cv; bool eof = false;
  std::thread writer([&] {
    for (;;) {
      std::unique_lock lk(mu);
      cv.wait(lk, [&] { return !q.empty() || eof || stop; });
      if (q.empty()) { if (eof || stop) break; continue; }
      auto due = q.front().due;
      if (Clock::now() < due) { cv.wait_until(lk, due); continue; }
      Item it = std::move(q.front()); q.pop_front(); lk.unlock();
      size_t off = 0;
      while (off < it.data.size()) { ssize_t n = ::send(dst, it.data.data() + off, it.data.size() - off, MSG_NOSIGNAL); if (n <= 0) { ::shutdown(src, SHUT_RDWR); return; } off += size_t(n); }
    }
    ::shutdown(dst, SHUT_WR);
  });
  uint8_t buf[65536];
  for (;;) {
    ssize_t n = ::recv(src, buf, sizeof buf, 0);
    if (n <= 0 || stop) break;
    std::lock_guard lk(mu);
    q.push_back({Clock::now() + std::chrono::milliseconds(delay_ms), Bytes(buf, buf + n)});
    cv.notify_one();
  }
  { std::lock_guard lk(mu); eof = true; cv.notify_one(); }
  writer.join();
}

struct DelayProxy {
  TcpListener l{"127.0.0.1", 0};
  uint16_t target; int delay;
  std::atomic<bool> stop{false};
  std::thread acceptor;
  std::mutex mu; std::vector<std::thread> conns; std::vector<int> fds;
  DelayProxy(uint16_t tgt, int d) : target(tgt), delay(d) {
    acceptor = std::thread([this] {
      while (!stop) {
        std::unique_ptr<Link> in;
        try { in = l.accept(100); } catch (...) { break; }
        if (!in) continue;
        auto out = tcp_connect("127.0.0.1", target, 3000);
        int a = in->release_fd(), b = out->release_fd();
        std::lock_guard lk(mu);
        fds.push_back(a); fds.push_back(b);
        conns.emplace_back([this, a, b] {
          std::thread t([&] { pump(b, a, delay, stop); });
          pump(a, b, delay, stop);
          t.join();
        });
      }
    });
  }
  ~DelayProxy() {
    stop = true; acceptor.join();
    { std::lock_guard lk(mu); for (int f : fds) ::shutdown(f, SHUT_RDWR); }
    for (auto& t : conns) t.join();
    for (int f : fds) ::close(f);
  }
  uint16_t port() const { return l.port(); }
};

struct Node {
  fs::path dir; Identity id; Catalog cat; ContentStore store; NodeContext ctx;
  explicit Node(const fs::path& d) : dir((fs::create_directories(d), d)), id(Identity::generate()),
      cat(d / "c.db"), store(d / "s"), ctx{id, cat, store, 1, {}} {}
};

double secs(Clock::time_point a) { return std::chrono::duration<double>(Clock::now() - a).count(); }

}  // namespace

int main(int argc, char** argv) {
  const size_t mib = argc > 1 ? size_t(std::stoul(argv[1])) : 32;
  const std::string out_md = argc > 2 ? argv[2] : "";
  auto root = fs::temp_directory_path() / ("wavenet_bench_" + std::to_string(::getpid()));
  fs::remove_all(root);
  // Source data: pseudo-random (incompressible).
  auto file = root / "data.bin";
  fs::create_directories(root);
  { std::ofstream f(file, std::ios::binary); uint64_t x = 88172645463325252ull; Bytes b(1 << 20);
    for (size_t i = 0; i < mib; ++i) { for (auto& c : b) { x ^= x << 13; x ^= x >> 7; x ^= x << 17; c = uint8_t(x); } f.write(reinterpret_cast<char*>(b.data()), std::streamsize(b.size())); } }

  Node a(root / "a"), b(root / "b");
  auto m = a.store.import_file(file, {}, a.id);  // default 256 KiB chunks
  a.cat.put(m, LocalState::Published);
  NodeServer sa(a.ctx, "127.0.0.1", 0); sa.start();
  { Node tmp(root / "seed"); }
  if (!download(b.ctx, m.content_id, {{"127.0.0.1", sa.port()}}).complete) { std::fprintf(stderr, "replica failed\n"); return 1; }
  NodeServer sb(b.ctx, "127.0.0.1", 0); sb.start();

  std::string md = "| one-way delay | sources | window | connect+handshake (ms) | TTFB (ms) | total (s) | throughput (MiB/s) | resume 2nd half (s) |\n|---|---|---|---|---|---|---|---|\n";
  std::printf("file: %zu MiB, %u chunks of 256 KiB, TLS 1.3 sessions, single host loopback + emulated delay\n", mib, m.chunk_count());
  int run = 0;
  for (int delay : {0, 10, 25}) {
    DelayProxy pa(sa.port(), delay), pb(sb.port(), delay);
    for (int nsrc : {1, 2}) {
      for (uint32_t win : {1u, 4u, 16u}) {
        std::vector<PeerAddr> peers{{"127.0.0.1", pa.port()}};
        if (nsrc == 2) peers.push_back({"127.0.0.1", pb.port()});
        // connect + mutual handshake time (one session)
        Node probe(root / ("p" + std::to_string(run)));
        auto t0 = Clock::now();
        { PeerClient pc(peers[0], probe.ctx); }
        double hs = secs(t0) * 1000;
        // full download
        Node e(root / ("e" + std::to_string(run)));
        auto st = download(e.ctx, m.content_id, peers, nullptr, {win});
        if (!st.complete || sha256_file(e.store.blob_path(m.content_id)) != m.content_id) { std::fprintf(stderr, "download failed: %s\n", st.error.c_str()); return 1; }
        // resume: stop at 50%, then measure completion
        Node r(root / ("r" + std::to_string(run)));
        uint32_t half = m.chunk_count() / 2;
        download(r.ctx, m.content_id, peers, [&](uint32_t d, uint32_t) { return d < half; }, {win});
        auto rs = download(r.ctx, m.content_id, peers, nullptr, {win});
        if (!rs.complete) { std::fprintf(stderr, "resume failed\n"); return 1; }
        double thr = double(st.bytes_fetched) / st.seconds / (1 << 20);
        char line[256];
        std::snprintf(line, sizeof line, "| %d ms | %d | %u | %.1f | %.1f | %.3f | %.1f | %.3f |\n", delay, nsrc, win, hs,
                      st.first_chunk_seconds * 1000, st.seconds, thr, rs.seconds);
        std::fputs(line, stdout); std::fflush(stdout);
        md += line;
        fs::remove_all(probe.dir); fs::remove_all(e.dir); fs::remove_all(r.dir);
        ++run;
      }
    }
  }
  sa.stop(); sb.stop();
  if (!out_md.empty()) std::ofstream(out_md) << md;
  fs::remove_all(root);
  return 0;
}
