// Transport abstraction (W1 Radio Fabric). TCP implemented; BLE/Wi-Fi Direct/Aware plug in here later.
#pragma once
#include <memory>
#include <string>
#include "wavenet/bytes.hpp"

namespace wavenet {

struct TransportError : std::runtime_error { using std::runtime_error::runtime_error; };

// A reliable, ordered byte link that carries whole WAVE-NP frames.
class Link {
 public:
  virtual ~Link() = default;
  virtual void send_frame(ByteView frame) = 0;            // throws TransportError
  virtual Bytes recv_frame(int timeout_ms) = 0;           // throws TransportError (incl. timeout)
  virtual void close() = 0;
  virtual std::string describe() const = 0;               // e.g. "tcp:127.0.0.1:4000"
  virtual const char* medium() const = 0;                 // "tcp", later "ble", "wifi-direct", ...
  // Channel-binding value (RFC 9266-style TLS exporter) mixed into WAVE-NP signatures; empty if unencrypted.
  virtual Bytes channel_binding() const { return {}; }
  virtual bool encrypted() const { return false; }
  // Hands the underlying socket to a wrapper (e.g. TLS). Returns -1 if not supported. Link is unusable afterwards.
  virtual int release_fd() { return -1; }
};

class TcpListener {
 public:
  TcpListener(const std::string& bind_host, uint16_t port);  // port 0 = ephemeral
  ~TcpListener();
  uint16_t port() const { return port_; }
  // Returns nullptr on timeout.
  std::unique_ptr<Link> accept(int timeout_ms);
  void close();
 private:
  int fd_ = -1;
  uint16_t port_ = 0;
};

std::unique_ptr<Link> tcp_connect(const std::string& host, uint16_t port, int timeout_ms);

// TLS 1.3 (OpenSSL) over an existing TCP link. Certificates are ephemeral and NOT verified: peer identity is
// proven by the WAVE-NP handshake, which signs the TLS exporter value (channel binding), so a man-in-the-middle
// that terminates TLS on both sides cannot produce valid signatures.
enum class TlsRole { Client, Server };
std::unique_ptr<Link> tls_wrap(std::unique_ptr<Link> tcp, TlsRole role, int handshake_timeout_ms = 5000);

}  // namespace wavenet
