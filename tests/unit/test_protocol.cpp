#include "test.hpp"
#include "wavenet/protocol.hpp"
using namespace wavenet;
using namespace wavenet::np;

static Frame sample(const Identity& id) {
  Frame f; f.h.type = MsgType::SEARCH_REQUEST; f.h.src = id.public_key(); f.h.network_id = 7;
  f.h.request_id = 42; f.h.seq = 3; f.h.timestamp_ms = 1234; f.h.hop_limit = 4;
  f.payload = {1, 2, 3, 4, 5};
  return f;
}

TEST(np_roundtrip) {
  auto id = Identity::generate();
  auto w = encode(sample(id), id);
  CHECK_EQ(w.size(), size_t(kBaseHeaderLen) + 5 + kSigLen);
  CHECK_EQ(peek_frame_size(w), w.size());
  auto f = decode(w);
  CHECK(f.h.type == MsgType::SEARCH_REQUEST);
  CHECK_EQ(f.h.network_id, 7u); CHECK_EQ(f.h.request_id, 42u); CHECK_EQ(f.h.seq, 3u);
  CHECK_EQ(f.h.hop_limit, 4); CHECK(f.payload == Bytes({1, 2, 3, 4, 5}));
  CHECK(f.src_id() == id.node_id());
}

TEST(np_rejects_every_single_bit_flip) {
  auto id = Identity::generate();
  auto w = encode(sample(id), id);
  int accepted = 0;
  for (size_t i = 0; i < w.size(); ++i) {
    for (int bit = 0; bit < 8; ++bit) {
      auto c = w; c[i] ^= uint8_t(1 << bit);
      try { decode(c); ++accepted; } catch (const DecodeError&) {}
    }
  }
  CHECK_EQ(accepted, 0);
}

TEST(np_context_binding) {
  auto id = Identity::generate();
  Bytes nonceA(32, 0xAA), nonceB(32, 0xBB);
  auto w = encode(sample(id), id, nonceA);
  CHECK(decode(w, nonceA).h.seq == 3);
  CHECK_THROWS(decode(w, nonceB));  // replay into another session fails
  CHECK_THROWS(decode(w));
}

TEST(np_limits) {
  auto id = Identity::generate();
  Frame f = sample(id); f.payload.assign(kMaxPayload + 1, 0);
  CHECK_THROWS(encode(f, id));
  auto w = encode(sample(id), id);
  CHECK_THROWS(decode(ByteView(w).first(w.size() - 1)));
  // forge an oversized payload length
  auto c = w; c[kBaseHeaderLen - 4] = 0xff; c[kBaseHeaderLen - 1] = 0x7f;
  CHECK_THROWS(peek_frame_size(c));
  auto other = Identity::generate();
  Frame wrong = sample(id);
  CHECK_THROWS(encode(wrong, other));  // src must match signer
  Bytes junk(200, 0);
  CHECK_THROWS(decode(junk));
}
