#include "wavenet/protocol.hpp"
#include <chrono>

namespace wavenet::np {

static constexpr std::string_view kDomain = "WAVE-NP/1 frame";
// payload_len offset inside header: everything before it in base header
static constexpr size_t kPayloadLenOff = kBaseHeaderLen - 4;

uint64_t now_ms() {
  using namespace std::chrono;
  return uint64_t(duration_cast<milliseconds>(system_clock::now().time_since_epoch()).count());
}

const char* type_name(MsgType t) {
  switch (t) {
#define N(x) case MsgType::x: return #x;
    N(NODE_ANNOUNCE) N(NODE_CAPABILITY) N(LINK_REPORT) N(HELLO) N(HELLO_ACK) N(ERROR)
    N(SERVICE_ADVERTISE) N(CONTENT_ADVERTISE) N(SEARCH_REQUEST) N(SEARCH_RESPONSE)
    N(MANIFEST_REQUEST) N(MANIFEST_RESPONSE) N(INTENT_REQUEST) N(ROUTE_PROPOSAL) N(ROUTE_COMMIT)
    N(CHUNK_REQUEST) N(DATA_CHUNK) N(CHUNK_RECEIPT) N(STATE_CHECKPOINT) N(SERVICE_RECOVER)
    N(GATEWAY_ADVERTISE) N(TRUST_REVOKE)
#undef N
  }
  return "UNKNOWN";
}

static Bytes signing_input(ByteView body, ByteView context) {
  Writer w; w.str(kDomain); w.blob(context); w.raw(body); return w.take();
}

Bytes encode(const Frame& f, const Identity& signer, ByteView context) {
  if (f.payload.size() > kMaxPayload) throw std::length_error("payload too large");
  if (f.h.src != signer.public_key()) throw std::invalid_argument("header src != signer");
  Writer w;
  w.u32(kMagic); w.u8(f.h.version); w.u16(uint16_t(f.h.type)); w.u8(f.h.flags);
  w.u16(kBaseHeaderLen); w.u32(f.h.network_id); w.raw(f.h.src); w.raw(f.h.dst);
  w.u64(f.h.request_id); w.u64(f.h.seq); w.u64(f.h.timestamp_ms); w.u8(f.h.hop_limit);
  w.u32(uint32_t(f.payload.size()));
  w.raw(f.payload);
  auto sig = signer.sign(signing_input(w.data(), context));
  w.raw(sig);
  return w.take();
}

size_t peek_frame_size(ByteView p) {
  Reader r(p);
  if (r.u32() != kMagic) throw DecodeError("bad magic");
  uint8_t ver = r.u8();
  if (ver != kVersion) throw DecodeError("unsupported version");
  r.u16(); r.u8();
  uint16_t hl = r.u16();
  if (hl < kBaseHeaderLen || hl > kMaxHeaderLen) throw DecodeError("bad header length");
  if (p.size() < kPayloadLenOff + 4) return 0;  // need more bytes to know the size
  Reader rl(p.subspan(kPayloadLenOff, 4));
  uint32_t pl = rl.u32();
  if (pl > kMaxPayload) throw DecodeError("payload too large");
  return size_t(hl) + pl + kSigLen;
}

Frame decode(ByteView wire, ByteView context) {
  if (wire.size() > kMaxFrame) throw DecodeError("frame too large");
  if (wire.size() < kBaseHeaderLen + kSigLen) throw DecodeError("frame too short");
  Reader r(wire);
  Frame f;
  if (r.u32() != kMagic) throw DecodeError("bad magic");
  f.h.version = r.u8();
  if (f.h.version != kVersion) throw DecodeError("unsupported version");
  f.h.type = MsgType(r.u16());
  f.h.flags = r.u8();
  uint16_t hl = r.u16();
  if (hl < kBaseHeaderLen || hl > kMaxHeaderLen) throw DecodeError("bad header length");
  f.h.network_id = r.u32();
  f.h.src = r.fixed<32>();
  f.h.dst = r.fixed<32>();
  f.h.request_id = r.u64(); f.h.seq = r.u64(); f.h.timestamp_ms = r.u64(); f.h.hop_limit = r.u8();
  uint32_t pl = r.u32();
  if (pl > kMaxPayload) throw DecodeError("payload too large");
  r.raw(hl - kBaseHeaderLen);  // forward-compatible header extensions are skipped (but signed)
  if (r.remaining() != size_t(pl) + kSigLen) throw DecodeError("length mismatch");
  auto pv = r.raw(pl);
  f.payload.assign(pv.begin(), pv.end());
  auto sig = r.fixed<64>();
  auto body = wire.first(wire.size() - kSigLen);
  if (!verify_signature(f.h.src, signing_input(body, context), sig)) throw DecodeError("bad signature");
  return f;
}

}  // namespace wavenet::np
