// Byte buffers, hex, and a bounds-checked little-endian codec used by WAVE-NP.
#pragma once
#include <array>
#include <cstdint>
#include <span>
#include <stdexcept>
#include <string>
#include <string_view>
#include <vector>

namespace wavenet {

using Bytes = std::vector<uint8_t>;
using ByteView = std::span<const uint8_t>;

struct DecodeError : std::runtime_error { using std::runtime_error::runtime_error; };

std::string to_hex(ByteView b);
Bytes from_hex(std::string_view hex);  // throws DecodeError
inline ByteView as_view(std::string_view s) { return {reinterpret_cast<const uint8_t*>(s.data()), s.size()}; }

class Writer {
 public:
  void u8(uint8_t v) { buf_.push_back(v); }
  void u16(uint16_t v) { put_le(v, 2); }
  void u32(uint32_t v) { put_le(v, 4); }
  void u64(uint64_t v) { put_le(v, 8); }
  void raw(ByteView b) { buf_.insert(buf_.end(), b.begin(), b.end()); }
  // Length-prefixed (u32) blob / string.
  void blob(ByteView b);
  void str(std::string_view s) { blob(as_view(s)); }
  const Bytes& data() const { return buf_; }
  Bytes take() { return std::move(buf_); }
 private:
  void put_le(uint64_t v, int n) { for (int i = 0; i < n; ++i) buf_.push_back(uint8_t(v >> (8 * i))); }
  Bytes buf_;
};

class Reader {
 public:
  explicit Reader(ByteView b, size_t max_blob = 16u << 20) : b_(b), max_blob_(max_blob) {}
  uint8_t u8() { return uint8_t(get_le(1)); }
  uint16_t u16() { return uint16_t(get_le(2)); }
  uint32_t u32() { return uint32_t(get_le(4)); }
  uint64_t u64() { return get_le(8); }
  ByteView raw(size_t n);
  template <size_t N> std::array<uint8_t, N> fixed() {
    auto v = raw(N); std::array<uint8_t, N> a{}; std::copy(v.begin(), v.end(), a.begin()); return a;
  }
  Bytes blob();
  std::string str();
  size_t remaining() const { return b_.size() - pos_; }
  bool done() const { return pos_ == b_.size(); }
  void expect_done() const { if (!done()) throw DecodeError("trailing bytes"); }
 private:
  uint64_t get_le(int n);
  ByteView b_;
  size_t pos_ = 0;
  size_t max_blob_;
};

}  // namespace wavenet
