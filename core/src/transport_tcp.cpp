#include <arpa/inet.h>
#include <fcntl.h>
#include <netdb.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <poll.h>
#include <sys/socket.h>
#include <unistd.h>
#include <atomic>
#include <cerrno>
#include <cstring>
#include "wavenet/protocol.hpp"
#include "wavenet/transport.hpp"

namespace wavenet {

namespace {

bool wait_fd(int fd, short ev, int timeout_ms) {
  pollfd p{fd, ev, 0};
  for (;;) {
    int r = ::poll(&p, 1, timeout_ms);
    if (r < 0 && errno == EINTR) continue;
    if (r < 0) throw TransportError(std::string("poll: ") + std::strerror(errno));
    return r > 0;
  }
}

std::string peer_name(int fd) {
  sockaddr_storage ss{}; socklen_t l = sizeof ss;
  if (::getpeername(fd, reinterpret_cast<sockaddr*>(&ss), &l) != 0) return "tcp:?";
  char host[NI_MAXHOST], serv[NI_MAXSERV];
  if (::getnameinfo(reinterpret_cast<sockaddr*>(&ss), l, host, sizeof host, serv, sizeof serv, NI_NUMERICHOST | NI_NUMERICSERV) != 0) return "tcp:?";
  return std::string("tcp:") + host + ":" + serv;
}

class TcpLink final : public Link {
 public:
  explicit TcpLink(int fd) : fd_(fd), name_(peer_name(fd)) {
    int one = 1; ::setsockopt(fd_, IPPROTO_TCP, TCP_NODELAY, &one, sizeof one);
  }
  ~TcpLink() override { if (fd_ >= 0) { close(); ::close(fd_); } }
  int release_fd() override { int f = fd_; fd_ = -1; return f; }

  void send_frame(ByteView f) override {
    size_t off = 0;
    while (off < f.size()) {
      if (!wait_fd(fd_, POLLOUT, 30000)) throw TransportError("send timeout");
      ssize_t n = ::send(fd_, f.data() + off, f.size() - off, MSG_NOSIGNAL);
      if (n < 0 && (errno == EINTR || errno == EAGAIN)) continue;
      if (n <= 0) throw TransportError("send failed");
      off += size_t(n);
    }
  }

  Bytes recv_frame(int timeout_ms) override {
    Bytes buf(np::kBaseHeaderLen);
    read_exact(buf.data(), buf.size(), timeout_ms);
    size_t total;
    try { total = np::peek_frame_size(buf); } catch (const DecodeError& e) { throw TransportError(std::string("bad frame: ") + e.what()); }
    if (total < buf.size() || total > np::kMaxFrame) throw TransportError("bad frame size");
    size_t have = buf.size();
    buf.resize(total);
    read_exact(buf.data() + have, total - have, timeout_ms);
    return buf;
  }

  // Thread-safe: only shuts the socket down; the descriptor is released in the destructor so a
  // concurrent reader never sees a closed/reused fd.
  void close() override { if (!closed_.exchange(true) && fd_ >= 0) ::shutdown(fd_, SHUT_RDWR); }
  std::string describe() const override { return name_; }
  const char* medium() const override { return "tcp"; }

 private:
  void read_exact(uint8_t* p, size_t n, int timeout_ms) {
    size_t got = 0;
    while (got < n) {
      if (closed_) throw TransportError("closed");
      if (!wait_fd(fd_, POLLIN, timeout_ms)) throw TransportError("recv timeout");
      ssize_t r = ::recv(fd_, p + got, n - got, 0);
      if (r < 0 && (errno == EINTR || errno == EAGAIN)) continue;
      if (r == 0) throw TransportError("connection closed by peer");
      if (r < 0) throw TransportError("recv failed");
      got += size_t(r);
    }
  }
  int fd_;
  std::atomic<bool> closed_{false};
  std::string name_;
};

}  // namespace

TcpListener::TcpListener(const std::string& host, uint16_t port) {
  addrinfo hints{}; hints.ai_family = AF_UNSPEC; hints.ai_socktype = SOCK_STREAM; hints.ai_flags = AI_PASSIVE | AI_NUMERICSERV;
  addrinfo* res = nullptr;
  auto ps = std::to_string(port);
  if (::getaddrinfo(host.empty() ? nullptr : host.c_str(), ps.c_str(), &hints, &res) != 0 || !res)
    throw TransportError("cannot resolve bind address");
  fd_ = ::socket(res->ai_family, res->ai_socktype | SOCK_CLOEXEC, 0);
  int one = 1;
  if (fd_ < 0 || ::setsockopt(fd_, SOL_SOCKET, SO_REUSEADDR, &one, sizeof one) != 0 ||
      ::bind(fd_, res->ai_addr, res->ai_addrlen) != 0 || ::listen(fd_, 64) != 0) {
    ::freeaddrinfo(res); if (fd_ >= 0) ::close(fd_);
    throw TransportError(std::string("listen failed: ") + std::strerror(errno));
  }
  ::freeaddrinfo(res);
  sockaddr_storage ss{}; socklen_t l = sizeof ss;
  ::getsockname(fd_, reinterpret_cast<sockaddr*>(&ss), &l);
  port_ = ntohs(ss.ss_family == AF_INET6 ? reinterpret_cast<sockaddr_in6*>(&ss)->sin6_port
                                          : reinterpret_cast<sockaddr_in*>(&ss)->sin_port);
}
TcpListener::~TcpListener() { close(); }
void TcpListener::close() { if (fd_ >= 0) { ::shutdown(fd_, SHUT_RDWR); ::close(fd_); fd_ = -1; } }

std::unique_ptr<Link> TcpListener::accept(int timeout_ms) {
  if (fd_ < 0) throw TransportError("listener closed");
  if (!wait_fd(fd_, POLLIN, timeout_ms)) return nullptr;
  int c = ::accept4(fd_, nullptr, nullptr, SOCK_CLOEXEC);
  if (c < 0) return nullptr;
  return std::make_unique<TcpLink>(c);
}

std::unique_ptr<Link> tcp_connect(const std::string& host, uint16_t port, int timeout_ms) {
  addrinfo hints{}; hints.ai_family = AF_UNSPEC; hints.ai_socktype = SOCK_STREAM; hints.ai_flags = AI_NUMERICSERV;
  addrinfo* res = nullptr;
  auto ps = std::to_string(port);
  if (::getaddrinfo(host.c_str(), ps.c_str(), &hints, &res) != 0 || !res) throw TransportError("cannot resolve " + host);
  std::string err = "connect failed";
  for (auto* a = res; a; a = a->ai_next) {
    int fd = ::socket(a->ai_family, a->ai_socktype | SOCK_CLOEXEC | SOCK_NONBLOCK, 0);
    if (fd < 0) continue;
    int r = ::connect(fd, a->ai_addr, a->ai_addrlen);
    if (r != 0 && errno == EINPROGRESS) {
      if (wait_fd(fd, POLLOUT, timeout_ms)) {
        int e = 0; socklen_t l = sizeof e; ::getsockopt(fd, SOL_SOCKET, SO_ERROR, &e, &l);
        r = e == 0 ? 0 : -1; if (e) err = std::strerror(e);
      } else { err = "connect timeout"; }
    }
    if (r == 0) {
      int fl = ::fcntl(fd, F_GETFL); ::fcntl(fd, F_SETFL, fl & ~O_NONBLOCK);
      ::freeaddrinfo(res);
      return std::make_unique<TcpLink>(fd);
    }
    ::close(fd);
  }
  ::freeaddrinfo(res);
  throw TransportError(err + " (" + host + ":" + ps + ")");
}

}  // namespace wavenet
