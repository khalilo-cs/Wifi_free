// Byte-level TCP tap used by security tests: forwards raw bytes both ways and records them.
#pragma once
#include <netinet/in.h>
#include <poll.h>
#include <sys/socket.h>
#include <unistd.h>
#include <algorithm>
#include <mutex>
#include <thread>
#include "wavenet/transport.hpp"

namespace {
using namespace wavenet;
// Plain byte relay (passive eavesdropper): forwards raw TCP bytes both ways and records everything.
struct Tap {
  TcpListener l{"127.0.0.1", 0};
  uint16_t target;
  Bytes seen;
  std::mutex mu;
  std::thread t;
  explicit Tap(uint16_t tgt) : target(tgt) {
    t = std::thread([this] {
      auto in = l.accept(5000);
      if (!in) return;
      auto out = tcp_connect("127.0.0.1", target, 3000);
      int a = in->release_fd(), b = out->release_fd();
      uint8_t buf[65536];
      for (;;) {
        pollfd p[2] = {{a, POLLIN, 0}, {b, POLLIN, 0}};
        if (::poll(p, 2, 3000) <= 0) break;
        bool done = false;
        for (int i = 0; i < 2; ++i) {
          if (!(p[i].revents & (POLLIN | POLLHUP))) continue;
          ssize_t n = ::recv(p[i].fd, buf, sizeof buf, 0);
          if (n <= 0) { done = true; break; }
          { std::lock_guard lk(mu); seen.insert(seen.end(), buf, buf + n); }
          ::send(i == 0 ? b : a, buf, size_t(n), MSG_NOSIGNAL);
        }
        if (done) break;
      }
      ::close(a); ::close(b);
    });
  }
  ~Tap() { if (t.joinable()) t.join(); }
  bool saw(const std::string& s) {
    std::lock_guard lk(mu);
    return std::search(seen.begin(), seen.end(), s.begin(), s.end()) != seen.end();
  }
};

}  // namespace
