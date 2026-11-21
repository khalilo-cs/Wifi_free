// W3 multi-hop relaying (T15). A relay splices raw bytes; the client then runs TLS 1.3 + WAVE-NP end-to-end
// with the target THROUGH the splice, so relays see only metadata (who connects to whom, sizes, timing).
#pragma once
#include <atomic>
#include "wavenet/protocol.hpp"
#include "wavenet/transport.hpp"

namespace wavenet {

// A connection is a relay request iff it starts with a plaintext WAVE-NP frame of type ROUTE_PROPOSAL
// (TLS starts with 0x16; a plaintext session starts with a HELLO frame).
bool is_route_request(ByteView first7);  // magic(4) version(1) type(2)
constexpr size_t kRouteSniffLen = 7;

// Client side: asks the node at the far end of `raw` to splice it to `next_hop`. Returns the same link, now
// connected (through the relay) to next_hop's listener. Throws SessionError/TransportError on refusal.
std::unique_ptr<Link> route_through(std::unique_ptr<Link> raw, const Identity& me, uint32_t network_id,
                                    const NodeId& next_hop, int timeout_ms);

// Copies bytes both ways until either side closes, `idle_ms` passes without traffic, or `stop` is set.
// Returns total bytes forwarded.
uint64_t splice_fds(int a, int b, int idle_ms, const std::atomic<bool>& stop);

}  // namespace wavenet
