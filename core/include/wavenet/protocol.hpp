// WAVE-NP v1 — experimental project protocol (NOT an Internet standard).
// Frame = header | payload | Ed25519 signature(64)
#pragma once
#include <optional>
#include "wavenet/identity.hpp"

namespace wavenet::np {

constexpr uint32_t kMagic = 0x504E5657;  // "WVNP" little-endian
constexpr uint8_t kVersion = 1;
constexpr uint16_t kBaseHeaderLen = 4 + 1 + 2 + 1 + 2 + 4 + 32 + 32 + 8 + 8 + 8 + 1 + 4;  // 107
constexpr uint16_t kMaxHeaderLen = 1024;
constexpr uint32_t kMaxPayload = (1u << 20) + (64u << 10);  // 1 MiB chunk + metadata
constexpr size_t kSigLen = 64;
constexpr size_t kMaxFrame = kMaxHeaderLen + kMaxPayload + kSigLen;

enum class MsgType : uint16_t {
  // Control / discovery
  NODE_ANNOUNCE = 0x0001, NODE_CAPABILITY = 0x0002, LINK_REPORT = 0x0003,
  HELLO = 0x0010, HELLO_ACK = 0x0011, ERROR = 0x001F,
  // Service / content
  SERVICE_ADVERTISE = 0x0100, CONTENT_ADVERTISE = 0x0101,
  SEARCH_REQUEST = 0x0110, SEARCH_RESPONSE = 0x0111,
  MANIFEST_REQUEST = 0x0120, MANIFEST_RESPONSE = 0x0121,
  // Intent / routing (reserved, not implemented yet)
  INTENT_REQUEST = 0x0200, ROUTE_PROPOSAL = 0x0201, ROUTE_COMMIT = 0x0202,
  // Data
  CHUNK_REQUEST = 0x0300, DATA_CHUNK = 0x0301, CHUNK_RECEIPT = 0x0302,
  // Continuity / interconnect / trust (reserved)
  STATE_CHECKPOINT = 0x0400, SERVICE_RECOVER = 0x0401,
  GATEWAY_ADVERTISE = 0x0500, TRUST_REVOKE = 0x0600,
};
const char* type_name(MsgType t);

struct Header {
  uint8_t version = kVersion;
  MsgType type{};
  uint8_t flags = 0;
  uint32_t network_id = 0;
  PublicKey src{};         // sender's Ed25519 public key
  NodeId dst{};            // all-zero = broadcast / link peer
  uint64_t request_id = 0; // correlates request/response
  uint64_t seq = 0;        // per-session strictly increasing (replay protection)
  uint64_t timestamp_ms = 0;
  uint8_t hop_limit = 1;
};

struct Frame {
  Header h;
  Bytes payload;
  NodeId src_id() const { return node_id_of(h.src); }
};

// Builds and signs a frame. `context` (e.g. receiver's session nonce) is signed but not transmitted.
Bytes encode(const Frame& f, const Identity& signer, ByteView context = {});

// Reads the total frame length from a buffer prefix (needs >= 12 bytes). Throws DecodeError if invalid.
size_t peek_frame_size(ByteView prefix);
constexpr size_t kPeekLen = 4 + 1 + 2 + 1 + 2 + 2;  // up to header_len + room

// Parses, validates limits and verifies the signature with the same `context`.
// Throws DecodeError on any malformed, oversized, or wrongly-signed frame.
Frame decode(ByteView wire, ByteView context = {});

uint64_t now_ms();

}  // namespace wavenet::np
