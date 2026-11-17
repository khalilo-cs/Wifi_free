// libFuzzer target: WAVE-NP frame decoder must never crash on arbitrary input.
#include "wavenet/protocol.hpp"
extern "C" int LLVMFuzzerTestOneInput(const uint8_t* d, size_t n) {
  wavenet::ByteView v(d, n);
  try { (void)wavenet::np::peek_frame_size(v); } catch (const wavenet::DecodeError&) {}
  try { (void)wavenet::np::decode(v); } catch (const wavenet::DecodeError&) {}
  return 0;
}
