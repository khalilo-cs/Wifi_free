#include "test.hpp"
#include "wavenet/session.hpp"
#include <netinet/in.h>
#include <poll.h>
#include <sys/socket.h>
#include <unistd.h>
#include <algorithm>
#include <mutex>
#include <thread>
using namespace wavenet;
using np::MsgType;

namespace {
const std::string kMarker = "WAVE-SECRET-MARKER-7f3a9c";

// Plain byte relay (passive eavesdropper): forwards raw TCP bytes both ways and records everything.
struct Tap {
  TcpListener l{"127.0.0.1", 0};
  uint16_t target;
  Bytes seen;
  std::mutex mu;
  std::thread t;
  explicit Tap(uint16_t tgt) : target(tgt) {
    t = std::thread([this] {
      auto in = l.accept(3000);
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

// Runs a server that answers one SEARCH_REQUEST echo; client sends the marker through `connect_port`.
bool exchange_through(uint16_t server_port_unused, TcpListener& srv_l, uint16_t connect_port, bool tls) {
  auto a = Identity::generate(), b = Identity::generate();
  bool ok = false;
  std::thread srv([&] {
    try {
      auto link = srv_l.accept(3000);
      if (tls) link = tls_wrap(std::move(link), TlsRole::Server);
      auto s = Session::respond(std::move(link), b, 1, {});
      auto f = s->recv(3000);
      s->send(MsgType::SEARCH_RESPONSE, f.payload, f.h.request_id);
    } catch (...) {}
  });
  try {
    auto link = tcp_connect("127.0.0.1", connect_port, 3000);
    if (tls) link = tls_wrap(std::move(link), TlsRole::Client);
    auto c = Session::initiate(std::move(link), a, 1, {});
    c->send(MsgType::SEARCH_REQUEST, as_view(kMarker), 1);
    auto r = c->recv(3000);
    ok = std::string(r.payload.begin(), r.payload.end()) == kMarker;
    c->close();
  } catch (...) {}
  srv.join();
  (void)server_port_unused;
  return ok;
}
}  // namespace

TEST(tls_session_encrypted_and_bound) {
  auto a = Identity::generate(), b = Identity::generate();
  TcpListener l("127.0.0.1", 0);
  std::unique_ptr<Session> srv;
  std::thread t([&] { srv = Session::respond(tls_wrap(l.accept(3000), TlsRole::Server), b, 1, {}); });
  auto cli = Session::initiate(tls_wrap(tcp_connect("127.0.0.1", l.port(), 3000), TlsRole::Client), a, 1, {});
  t.join();
  CHECK(cli->link().encrypted());
  CHECK_EQ(std::string(cli->link().medium()), std::string("tcp+tls1.3"));
  CHECK_EQ(cli->link().channel_binding().size(), size_t(32));
  CHECK(cli->link().channel_binding() == srv->link().channel_binding());
  Bytes big(1 << 20, 0x42);
  cli->send(MsgType::DATA_CHUNK, big, 5);
  CHECK(srv->recv(5000).payload == big);
}

TEST(tls_passive_eavesdropper_sees_no_plaintext) {
  {  // control: without TLS the tap DOES see the marker (proves the capture works)
    TcpListener sl("127.0.0.1", 0);
    Tap tap(sl.port());
    CHECK(exchange_through(sl.port(), sl, tap.l.port(), false));
    tap.t.join();
    CHECK(tap.saw(kMarker));
  }
  {
    TcpListener sl("127.0.0.1", 0);
    Tap tap(sl.port());
    CHECK(exchange_through(sl.port(), sl, tap.l.port(), true));
    tap.t.join();
    CHECK(!tap.saw(kMarker));
    CHECK(tap.seen.size() > 0);
  }
}

TEST(tls_active_mitm_detected_by_channel_binding) {
  // Attacker terminates TLS on both sides and relays WAVE-NP frames unchanged.
  auto a = Identity::generate(), b = Identity::generate();
  TcpListener real("127.0.0.1", 0), mitm("127.0.0.1", 0);
  std::string server_err, client_err;
  std::thread srv([&] {
    try { Session::respond(tls_wrap(real.accept(3000), TlsRole::Server), b, 1, {}); server_err = "accepted"; }
    catch (const std::exception& e) { server_err = e.what(); }
  });
  std::thread attacker([&] {
    try {
      auto from_client = tls_wrap(mitm.accept(3000), TlsRole::Server);
      auto to_server = tls_wrap(tcp_connect("127.0.0.1", real.port(), 3000), TlsRole::Client);
      to_server->send_frame(from_client->recv_frame(3000));  // relay HELLO
      from_client->send_frame(to_server->recv_frame(3000));  // relay reply (ACK or ERROR)
    } catch (...) {}
  });
  try { Session::initiate(tls_wrap(tcp_connect("127.0.0.1", mitm.port(), 3000), TlsRole::Client), a, 1, {}); client_err = "accepted"; }
  catch (const std::exception& e) { client_err = e.what(); }
  srv.join(); attacker.join();
  CHECK(server_err != "accepted");
  CHECK(client_err != "accepted");
  CHECK(server_err.find("bad signature") != std::string::npos);
  std::printf("  server: %s | client: %s\n", server_err.c_str(), client_err.c_str());
}
