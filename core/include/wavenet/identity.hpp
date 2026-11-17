// Node identity: Ed25519 key pair (OpenSSL). NodeID = SHA-256(raw public key).
#pragma once
#include <array>
#include <filesystem>
#include <memory>
#include "wavenet/hash.hpp"

namespace wavenet {

using PublicKey = std::array<uint8_t, 32>;
using Signature = std::array<uint8_t, 64>;
using NodeId = Hash256;

NodeId node_id_of(const PublicKey& pk);
bool verify_signature(const PublicKey& pk, ByteView msg, const Signature& sig);
void random_bytes(uint8_t* out, size_t n);  // OpenSSL CSPRNG; throws on failure

class Identity {
 public:
  static Identity generate();
  // Loads <dir>/node.key (PEM, PKCS#8). Throws if missing/invalid or permissions too open.
  static Identity load(const std::filesystem::path& dir);
  // Loads if present, else generates and saves with mode 0600.
  static Identity load_or_create(const std::filesystem::path& dir);
  void save(const std::filesystem::path& dir) const;

  const PublicKey& public_key() const { return pk_; }
  const NodeId& node_id() const { return id_; }
  Signature sign(ByteView msg) const;

  Identity(Identity&&) noexcept;
  Identity& operator=(Identity&&) noexcept;
  ~Identity();

 private:
  struct KeyDeleter { void operator()(void* k) const; };
  explicit Identity(void* pkey);
  std::unique_ptr<void, KeyDeleter> key_;
  PublicKey pk_{};
  NodeId id_{};
};

}  // namespace wavenet
