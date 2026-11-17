#include "test.hpp"
#include "wavenet/hash.hpp"
#include <filesystem>
#include <fstream>
using namespace wavenet;

TEST(hex_roundtrip) {
  Bytes b{0x00, 0x7f, 0x80, 0xff};
  CHECK_EQ(to_hex(b), std::string("007f80ff"));
  CHECK(from_hex("007F80ff") == b);
  CHECK_THROWS(from_hex("abc"));
  CHECK_THROWS(from_hex("zz"));
}

TEST(sha256_known_vectors) {  // FIPS 180-2 test vectors
  CHECK_EQ(hex(sha256(as_view(""))), std::string("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"));
  CHECK_EQ(hex(sha256(as_view("abc"))), std::string("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"));
}

TEST(sha256_file_matches_memory) {
  auto p = std::filesystem::temp_directory_path() / "wavenet_hash_test.bin";
  Bytes data(300000); for (size_t i = 0; i < data.size(); ++i) data[i] = uint8_t(i * 31);
  { std::ofstream f(p, std::ios::binary); f.write(reinterpret_cast<char*>(data.data()), std::streamsize(data.size())); }
  CHECK(sha256_file(p) == sha256(data));
  std::filesystem::remove(p);
}

TEST(codec_roundtrip_and_bounds) {
  Writer w; w.u8(1); w.u16(0x0203); w.u32(0x04050607); w.u64(0x08090a0b0c0d0e0fULL); w.str("hello");
  Reader r(w.data());
  CHECK_EQ(r.u8(), 1); CHECK_EQ(r.u16(), 0x0203); CHECK_EQ(r.u32(), 0x04050607u);
  CHECK_EQ(r.u64(), 0x08090a0b0c0d0e0fULL); CHECK_EQ(r.str(), std::string("hello"));
  CHECK(r.done());
  Bytes trunc(w.data().begin(), w.data().end() - 2);
  Reader r2(trunc); r2.u8(); r2.u16(); r2.u32(); r2.u64();
  CHECK_THROWS(r2.str());
  Writer big; big.u32(1000);  // claims 1000 bytes, has none
  Reader r3(big.data(), 100);
  CHECK_THROWS(r3.blob());
}
