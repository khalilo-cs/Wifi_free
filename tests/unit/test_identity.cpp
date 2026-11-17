#include "test.hpp"
#include <unistd.h>
#include "wavenet/identity.hpp"
#include <filesystem>
using namespace wavenet;
namespace fs = std::filesystem;

static fs::path tmpdir(const char* n) {
  auto p = fs::temp_directory_path() / (std::string("wavenet_") + n + std::to_string(::getpid()));
  fs::remove_all(p); return p;
}

TEST(identity_sign_verify) {
  auto id = Identity::generate();
  auto sig = id.sign(as_view("hello wave"));
  CHECK(verify_signature(id.public_key(), as_view("hello wave"), sig));
  CHECK(!verify_signature(id.public_key(), as_view("hello wavE"), sig));
  auto bad = sig; bad[10] ^= 1;
  CHECK(!verify_signature(id.public_key(), as_view("hello wave"), bad));
  auto other = Identity::generate();
  CHECK(!verify_signature(other.public_key(), as_view("hello wave"), sig));
  CHECK(id.node_id() == sha256(id.public_key()));
  CHECK(id.node_id() != other.node_id());
}

TEST(identity_persist_and_permissions) {
  auto d = tmpdir("id");
  auto a = Identity::load_or_create(d);
  auto st = fs::status(d / "node.key").permissions();
  CHECK((st & (fs::perms::group_all | fs::perms::others_all)) == fs::perms::none);
  auto b = Identity::load_or_create(d);
  CHECK(a.node_id() == b.node_id());
  fs::permissions(d / "node.key", fs::perms::others_read, fs::perm_options::add);
  CHECK_THROWS(Identity::load(d));  // refuses world-readable key
  fs::remove_all(d);
}

TEST(identity_rejects_garbage_key) {
  auto d = tmpdir("garbage");
  fs::create_directories(d);
  { FILE* f = std::fopen((d / "node.key").c_str(), "w"); std::fputs("not a key", f); std::fclose(f); }
  fs::permissions(d / "node.key", fs::perms::owner_read | fs::perms::owner_write, fs::perm_options::replace);
  CHECK_THROWS(Identity::load(d));
  fs::remove_all(d);
}

TEST(ed25519_rfc8032_test1) {  // RFC 8032 §7.1 TEST 1 (empty message)
  auto pkb = from_hex("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a");
  auto sgb = from_hex("e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b");
  PublicKey pk{}; std::copy(pkb.begin(), pkb.end(), pk.begin());
  Signature sg{}; std::copy(sgb.begin(), sgb.end(), sg.begin());
  CHECK(verify_signature(pk, ByteView{}, sg));
  CHECK(!verify_signature(pk, as_view("x"), sg));
}
