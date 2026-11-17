#include "wavenet/bytes.hpp"

namespace wavenet {

std::string to_hex(ByteView b) {
  static const char* d = "0123456789abcdef";
  std::string s; s.reserve(b.size() * 2);
  for (uint8_t c : b) { s.push_back(d[c >> 4]); s.push_back(d[c & 15]); }
  return s;
}

static int nib(char c) {
  if (c >= '0' && c <= '9') return c - '0';
  if (c >= 'a' && c <= 'f') return c - 'a' + 10;
  if (c >= 'A' && c <= 'F') return c - 'A' + 10;
  return -1;
}

Bytes from_hex(std::string_view h) {
  if (h.size() % 2) throw DecodeError("odd hex length");
  Bytes out(h.size() / 2);
  for (size_t i = 0; i < out.size(); ++i) {
    int a = nib(h[2 * i]), b = nib(h[2 * i + 1]);
    if (a < 0 || b < 0) throw DecodeError("invalid hex digit");
    out[i] = uint8_t(a << 4 | b);
  }
  return out;
}

void Writer::blob(ByteView b) {
  if (b.size() > UINT32_MAX) throw std::length_error("blob too large");
  u32(uint32_t(b.size()));
  raw(b);
}

ByteView Reader::raw(size_t n) {
  if (n > remaining()) throw DecodeError("truncated input");
  auto v = b_.subspan(pos_, n); pos_ += n; return v;
}

uint64_t Reader::get_le(int n) {
  auto v = raw(size_t(n));
  uint64_t r = 0;
  for (int i = 0; i < n; ++i) r |= uint64_t(v[size_t(i)]) << (8 * i);
  return r;
}

Bytes Reader::blob() {
  uint32_t n = u32();
  if (n > max_blob_) throw DecodeError("blob exceeds limit");
  auto v = raw(n); return Bytes(v.begin(), v.end());
}

std::string Reader::str() { auto b = blob(); return std::string(b.begin(), b.end()); }

}  // namespace wavenet
