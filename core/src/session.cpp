#include "wavenet/session.hpp"

namespace wavenet {
using np::MsgType;

static bool skew_ok(uint64_t ts) {
  uint64_t now = np::now_ms();
  return ts + kMaxClockSkewMs >= now && ts <= now + kMaxClockSkewMs;
}

np::Frame Session::make(MsgType t, ByteView payload, uint64_t req) {
  np::Frame f; f.h.type = t; f.h.network_id = net_; f.h.src = me_.public_key();
  f.h.request_id = req; f.h.seq = ++send_seq_; f.h.timestamp_ms = np::now_ms();
  f.payload.assign(payload.begin(), payload.end());
  return f;
}

Bytes Session::ctx(ByteView nonce) const {
  Bytes c(nonce.begin(), nonce.end());
  c.insert(c.end(), binding_.begin(), binding_.end());
  return c;
}

static Bytes fresh_nonce() { Bytes n(32); random_bytes(n.data(), n.size()); return n; }

std::unique_ptr<Session> Session::initiate(std::unique_ptr<Link> link, const Identity& me, uint32_t net, const TrustPolicy& pol) {
  std::unique_ptr<Session> s(new Session(std::move(link), me, net));
  s->binding_ = s->link_->channel_binding();
  s->my_nonce_ = fresh_nonce();
  s->link_->send_frame(np::encode(s->make(MsgType::HELLO, s->my_nonce_, 0), me, s->binding_));
  np::Frame ack;
  try { ack = np::decode(s->link_->recv_frame(kHandshakeTimeoutMs), s->ctx(s->my_nonce_)); }
  catch (const DecodeError& e) { throw SessionError(std::string("handshake rejected: ") + e.what()); }
  if (ack.h.type == MsgType::ERROR) throw SessionError("peer refused: " + std::string(ack.payload.begin(), ack.payload.end()));
  if (ack.h.type != MsgType::HELLO_ACK || ack.payload.size() != 32 || ack.h.network_id != net || !skew_ok(ack.h.timestamp_ms))
    throw SessionError("invalid HELLO_ACK");
  if (!pol.permits(ack.src_id())) throw SessionError("peer not trusted");
  if (ack.h.src == me.public_key()) throw SessionError("connected to self");
  s->peer_ = ack.h.src; s->peer_nonce_ = ack.payload; s->recv_seq_ = ack.h.seq;
  return s;
}

std::unique_ptr<Session> Session::respond(std::unique_ptr<Link> link, const Identity& me, uint32_t net, const TrustPolicy& pol) {
  std::unique_ptr<Session> s(new Session(std::move(link), me, net));
  s->binding_ = s->link_->channel_binding();
  np::Frame hello;
  try { hello = np::decode(s->link_->recv_frame(kHandshakeTimeoutMs), s->binding_); }
  catch (const DecodeError& e) { throw SessionError(std::string("bad HELLO: ") + e.what()); }
  if (hello.h.type != MsgType::HELLO || hello.payload.size() != 32) throw SessionError("expected HELLO");
  s->my_nonce_ = fresh_nonce();
  auto refuse = [&](const std::string& why) {
    std::string msg = why;
    try { s->link_->send_frame(np::encode(s->make(MsgType::ERROR, as_view(msg), 0), me, s->ctx(hello.payload))); } catch (...) {}
    throw SessionError(why);
  };
  if (hello.h.network_id != net) refuse("network id mismatch");
  if (!skew_ok(hello.h.timestamp_ms)) refuse("clock skew too large");
  if (!pol.permits(hello.src_id())) refuse("not trusted");
  if (hello.h.src == me.public_key()) refuse("self connection");
  s->peer_ = hello.h.src; s->peer_nonce_ = hello.payload; s->recv_seq_ = hello.h.seq;
  s->link_->send_frame(np::encode(s->make(MsgType::HELLO_ACK, s->my_nonce_, 0), me, s->ctx(s->peer_nonce_)));
  // NOTE: initiator's key possession is proven by its first frame signed over my_nonce_ (checked in recv()).
  return s;
}

void Session::send(MsgType t, ByteView payload, uint64_t req) {
  std::lock_guard lk(send_mu_);
  link_->send_frame(np::encode(make(t, payload, req), me_, ctx(peer_nonce_)));
}

np::Frame Session::recv(int timeout_ms) {
  auto wire = link_->recv_frame(timeout_ms);
  np::Frame f;
  try { f = np::decode(wire, ctx(my_nonce_)); }
  catch (const DecodeError& e) { link_->close(); throw SessionError(std::string("invalid frame: ") + e.what()); }
  if (f.h.src != peer_) { link_->close(); throw SessionError("frame from unexpected key"); }
  if (f.h.seq <= recv_seq_) { link_->close(); throw SessionError("replayed or reordered frame"); }
  if (f.h.network_id != net_) { link_->close(); throw SessionError("network id mismatch"); }
  recv_seq_ = f.h.seq;
  return f;
}

}  // namespace wavenet
