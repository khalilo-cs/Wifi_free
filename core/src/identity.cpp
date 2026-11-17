#include "wavenet/identity.hpp"
#include <fcntl.h>
#include <openssl/evp.h>
#include <openssl/pem.h>
#include <openssl/rand.h>
#include <sys/stat.h>
#include <unistd.h>
#include <cstdio>

namespace wavenet {
namespace fs = std::filesystem;

static EVP_PKEY* as_pkey(void* p) { return static_cast<EVP_PKEY*>(p); }
void Identity::KeyDeleter::operator()(void* k) const { EVP_PKEY_free(as_pkey(k)); }

void random_bytes(uint8_t* out, size_t n) {
  if (n > INT32_MAX || RAND_bytes(out, int(n)) != 1) throw std::runtime_error("CSPRNG failure");
}

NodeId node_id_of(const PublicKey& pk) { return sha256(pk); }

Identity::Identity(void* pkey) : key_(pkey) {
  size_t len = pk_.size();
  if (EVP_PKEY_get_raw_public_key(as_pkey(pkey), pk_.data(), &len) != 1 || len != 32)
    throw std::runtime_error("cannot extract Ed25519 public key");
  id_ = node_id_of(pk_);
}
Identity::Identity(Identity&&) noexcept = default;
Identity& Identity::operator=(Identity&&) noexcept = default;
Identity::~Identity() = default;

Identity Identity::generate() {
  EVP_PKEY* k = nullptr;
  EVP_PKEY_CTX* ctx = EVP_PKEY_CTX_new_id(EVP_PKEY_ED25519, nullptr);
  bool ok = ctx && EVP_PKEY_keygen_init(ctx) == 1 && EVP_PKEY_keygen(ctx, &k) == 1;
  EVP_PKEY_CTX_free(ctx);
  if (!ok) throw std::runtime_error("Ed25519 keygen failed");
  return Identity(k);
}

void Identity::save(const fs::path& dir) const {
  fs::create_directories(dir);
  fs::permissions(dir, fs::perms::owner_all, fs::perm_options::replace);
  auto path = dir / "node.key";
  int fd = ::open(path.c_str(), O_WRONLY | O_CREAT | O_TRUNC | O_NOFOLLOW, 0600);
  if (fd < 0) throw std::runtime_error("cannot create " + path.string());
  ::fchmod(fd, 0600);
  FILE* f = ::fdopen(fd, "w");
  if (!f) { ::close(fd); throw std::runtime_error("fdopen failed"); }
  bool ok = PEM_write_PrivateKey(f, as_pkey(key_.get()), nullptr, nullptr, 0, nullptr, nullptr) == 1;
  ok = (std::fclose(f) == 0) && ok;
  if (!ok) throw std::runtime_error("failed writing node key");
}

Identity Identity::load(const fs::path& dir) {
  auto path = dir / "node.key";
  struct stat st{};
  if (::lstat(path.c_str(), &st) != 0) throw std::runtime_error("no node key at " + path.string());
  if (!S_ISREG(st.st_mode)) throw std::runtime_error("node key is not a regular file");
  if (st.st_mode & 077) throw std::runtime_error("node key permissions too open (need 0600)");
  FILE* f = std::fopen(path.c_str(), "r");
  if (!f) throw std::runtime_error("cannot open node key");
  EVP_PKEY* k = PEM_read_PrivateKey(f, nullptr, nullptr, nullptr);
  std::fclose(f);
  if (!k) throw std::runtime_error("invalid node key file");
  if (EVP_PKEY_get_id(k) != EVP_PKEY_ED25519) { EVP_PKEY_free(k); throw std::runtime_error("node key is not Ed25519"); }
  return Identity(k);
}

Identity Identity::load_or_create(const fs::path& dir) {
  if (fs::exists(dir / "node.key")) return load(dir);
  auto id = generate(); id.save(dir); return id;
}

Signature Identity::sign(ByteView msg) const {
  Signature sig{}; size_t len = sig.size();
  EVP_MD_CTX* ctx = EVP_MD_CTX_new();
  bool ok = ctx && EVP_DigestSignInit(ctx, nullptr, nullptr, nullptr, as_pkey(key_.get())) == 1 &&
            EVP_DigestSign(ctx, sig.data(), &len, msg.data(), msg.size()) == 1 && len == 64;
  EVP_MD_CTX_free(ctx);
  if (!ok) throw std::runtime_error("Ed25519 sign failed");
  return sig;
}

bool verify_signature(const PublicKey& pk, ByteView msg, const Signature& sig) {
  EVP_PKEY* k = EVP_PKEY_new_raw_public_key(EVP_PKEY_ED25519, nullptr, pk.data(), pk.size());
  if (!k) return false;
  EVP_MD_CTX* ctx = EVP_MD_CTX_new();
  bool ok = ctx && EVP_DigestVerifyInit(ctx, nullptr, nullptr, nullptr, k) == 1 &&
            EVP_DigestVerify(ctx, sig.data(), sig.size(), msg.data(), msg.size()) == 1;
  EVP_MD_CTX_free(ctx);
  EVP_PKEY_free(k);
  return ok;
}

}  // namespace wavenet
