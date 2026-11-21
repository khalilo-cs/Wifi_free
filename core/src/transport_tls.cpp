// TLS 1.3 Link (OpenSSL). See transport.hpp for the trust model (channel binding, no PKI).
#include <fcntl.h>
#include <netdb.h>
#include <openssl/err.h>
#include <openssl/evp.h>
#include <openssl/ssl.h>
#include <openssl/x509.h>
#include <poll.h>
#include <sys/socket.h>
#include <unistd.h>
#include <csignal>
#include <atomic>
#include <cerrno>
#include <cstring>
#include <mutex>
#include "wavenet/protocol.hpp"
#include "wavenet/transport.hpp"

namespace wavenet {
namespace {

constexpr const char* kExporterLabel = "EXPORTER-WAVE-NP-v1";

std::string ssl_err() {
  unsigned long e = ERR_get_error();
  char b[256]; ERR_error_string_n(e, b, sizeof b);
  return e ? b : "unknown TLS error";
}

// Ephemeral self-signed Ed25519 certificate, one per process (only needed so TLS 1.3 can run; never trusted).
struct ServerCred {
  EVP_PKEY* key = nullptr;
  X509* cert = nullptr;
  ServerCred() {
    EVP_PKEY_CTX* kc = EVP_PKEY_CTX_new_id(EVP_PKEY_ED25519, nullptr);
    if (!kc || EVP_PKEY_keygen_init(kc) != 1 || EVP_PKEY_keygen(kc, &key) != 1) throw TransportError("TLS keygen failed");
    EVP_PKEY_CTX_free(kc);
    cert = X509_new();
    ASN1_INTEGER_set(X509_get_serialNumber(cert), 1);
    X509_gmtime_adj(X509_getm_notBefore(cert), -3600);
    X509_gmtime_adj(X509_getm_notAfter(cert), 3600L * 24 * 365);
    X509_set_pubkey(cert, key);
    X509_NAME* n = X509_get_subject_name(cert);
    X509_NAME_add_entry_by_txt(n, "CN", MBSTRING_ASC, reinterpret_cast<const unsigned char*>("wavenet-node"), -1, -1, 0);
    X509_set_issuer_name(cert, n);
    if (X509_sign(cert, key, nullptr) <= 0) throw TransportError("TLS self-sign failed");
  }
};

SSL_CTX* make_ctx(TlsRole role) {
  SSL_CTX* c = SSL_CTX_new(role == TlsRole::Server ? TLS_server_method() : TLS_client_method());
  if (!c) throw TransportError("SSL_CTX_new: " + ssl_err());
  SSL_CTX_set_min_proto_version(c, TLS1_3_VERSION);
  SSL_CTX_set_max_proto_version(c, TLS1_3_VERSION);
  SSL_CTX_set_options(c, SSL_OP_NO_COMPRESSION | SSL_OP_NO_TICKET);
  SSL_CTX_set_session_cache_mode(c, SSL_SESS_CACHE_OFF);
  if (role == TlsRole::Server) {
    static ServerCred cred;
    if (SSL_CTX_use_certificate(c, cred.cert) != 1 || SSL_CTX_use_PrivateKey(c, cred.key) != 1)
      throw TransportError("TLS cert setup: " + ssl_err());
  }
  SSL_CTX_set_verify(c, SSL_VERIFY_NONE, nullptr);  // identity proven by WAVE-NP signatures over the exporter
  return c;
}

SSL_CTX* ctx_for(TlsRole r) {
  // OpenSSL writes with write(), not send(MSG_NOSIGNAL): a closed peer would raise SIGPIPE and kill the
  // process. If the application left SIGPIPE at its default, ignore it so the error surfaces as EPIPE.
  static const bool sigpipe_once = [] {
    struct sigaction cur{};
    if (::sigaction(SIGPIPE, nullptr, &cur) == 0 && cur.sa_handler == SIG_DFL) ::signal(SIGPIPE, SIG_IGN);
    return true;
  }();
  (void)sigpipe_once;
  static SSL_CTX* server = make_ctx(TlsRole::Server);
  static SSL_CTX* client = make_ctx(TlsRole::Client);
  return r == TlsRole::Server ? server : client;
}

bool wait_fd(int fd, short ev, int timeout_ms) {
  pollfd p{fd, ev, 0};
  for (;;) {
    int r = ::poll(&p, 1, timeout_ms);
    if (r < 0 && errno == EINTR) continue;
    return r > 0;
  }
}

class TlsLink final : public Link {
 public:
  TlsLink(int fd, TlsRole role, std::string name, int timeout_ms) : fd_(fd), name_("tls+" + name) {
    ::fcntl(fd_, F_SETFL, ::fcntl(fd_, F_GETFL) | O_NONBLOCK);
    ssl_ = SSL_new(ctx_for(role));
    if (!ssl_ || SSL_set_fd(ssl_, fd_) != 1) { cleanup(); throw TransportError("SSL_new: " + ssl_err()); }
    role == TlsRole::Server ? SSL_set_accept_state(ssl_) : SSL_set_connect_state(ssl_);
    try {
      io([&] { return SSL_do_handshake(ssl_); }, timeout_ms, "TLS handshake");
    } catch (...) { cleanup(); throw; }
    if (SSL_version(ssl_) != TLS1_3_VERSION) { cleanup(); throw TransportError("peer did not negotiate TLS 1.3"); }
    binding_.resize(32);
    if (SSL_export_keying_material(ssl_, binding_.data(), binding_.size(), kExporterLabel, std::strlen(kExporterLabel),
                                   nullptr, 0, 0) != 1) { cleanup(); throw TransportError("TLS exporter failed"); }
  }
  ~TlsLink() override { close(); cleanup(); }

  void send_frame(ByteView f) override {
    size_t off = 0;
    while (off < f.size()) {
      size_t n = std::min<size_t>(f.size() - off, 1 << 20);
      int w = io([&] { return SSL_write(ssl_, f.data() + off, int(n)); }, 30000, "send");
      off += size_t(w);
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

  void close() override { if (!closed_.exchange(true) && fd_ >= 0) ::shutdown(fd_, SHUT_RDWR); }
  std::string describe() const override { return name_; }
  const char* medium() const override { return "tcp+tls1.3"; }
  Bytes channel_binding() const override { return binding_; }
  bool encrypted() const override { return true; }

 private:
  // Runs one OpenSSL operation, waiting on the socket as needed. SSL calls are serialised by a mutex
  // (an SSL object is not safe for concurrent use); waiting happens outside the lock.
  template <class F> int io(F op, int timeout_ms, const char* what) {
    for (;;) {
      if (closed_) throw TransportError("closed");
      int r, e;
      {
        std::lock_guard lk(mu_);
        ERR_clear_error();
        r = op();
        e = r > 0 ? SSL_ERROR_NONE : SSL_get_error(ssl_, r);
      }
      if (e == SSL_ERROR_NONE) return r;
      if (e == SSL_ERROR_WANT_READ) { if (!wait_fd(fd_, POLLIN, timeout_ms)) throw TransportError(std::string(what) + " timeout"); continue; }
      if (e == SSL_ERROR_WANT_WRITE) { if (!wait_fd(fd_, POLLOUT, timeout_ms)) throw TransportError(std::string(what) + " timeout"); continue; }
      if (e == SSL_ERROR_ZERO_RETURN) throw TransportError("connection closed by peer");
      throw TransportError(std::string(what) + " failed: " + ssl_err());
    }
  }
  void read_exact(uint8_t* p, size_t n, int timeout_ms) {
    size_t got = 0;
    while (got < n) {
      size_t want = std::min<size_t>(n - got, 1 << 20);
      got += size_t(io([&] { return SSL_read(ssl_, p + got, int(want)); }, timeout_ms, "recv"));
    }
  }
  void cleanup() {
    if (ssl_) { SSL_free(ssl_); ssl_ = nullptr; }
    if (fd_ >= 0) { ::close(fd_); fd_ = -1; }
  }
  int fd_;
  SSL* ssl_ = nullptr;
  std::mutex mu_;
  std::atomic<bool> closed_{false};
  std::string name_;
  Bytes binding_;
};

}  // namespace

std::unique_ptr<Link> tls_wrap(std::unique_ptr<Link> tcp, TlsRole role, int timeout_ms) {
  std::string name = tcp->describe();
  int fd = tcp->release_fd();
  if (fd < 0) throw TransportError("link cannot be wrapped in TLS");
  return std::make_unique<TlsLink>(fd, role, name, timeout_ms);
}

}  // namespace wavenet
