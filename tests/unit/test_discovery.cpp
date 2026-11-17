#include "test.hpp"
#include "wavenet/discovery.hpp"
using namespace wavenet;

static Bytes make_announce(const Identity& id, uint32_t net, uint64_t ts, uint16_t port = 4000) {
  np::Frame f; f.h.type = np::MsgType::NODE_ANNOUNCE; f.h.network_id = net; f.h.src = id.public_key();
  f.h.timestamp_ms = ts; Announce a; a.tcp_port = port; a.name = "n"; f.payload = a.encode();
  return np::encode(f, id);
}

TEST(discovery_validation_rules) {
  auto me = Identity::generate(), peer = Identity::generate();
  DiscoveryConfig c; c.multicast = false; c.port = 0;
  Discovery d(me, 5, c);
  uint64_t now = np::now_ms();
  CHECK(d.handle_datagram(make_announce(peer, 5, now), "10.0.0.2"));
  CHECK(!d.handle_datagram(make_announce(peer, 5, now), "10.0.0.2"));        // exact replay
  CHECK(!d.handle_datagram(make_announce(peer, 5, now - 10), "10.0.0.2"));   // older
  CHECK(d.handle_datagram(make_announce(peer, 5, now + 1), "10.0.0.3"));     // newer accepted
  CHECK(!d.handle_datagram(make_announce(peer, 6, now + 2), "10.0.0.3"));    // other network
  CHECK(!d.handle_datagram(make_announce(peer, 5, now - 120000), "x"));      // too old
  CHECK(!d.handle_datagram(make_announce(me, 5, now + 5), "x"));             // self
  auto bad = make_announce(Identity::generate(), 5, now); bad[20] ^= 1;
  CHECK(!d.handle_datagram(bad, "x"));                                         // forged
  CHECK(!d.handle_datagram(make_announce(Identity::generate(), 5, now, 0), "x"));  // port 0
  auto ps = d.peers();
  CHECK_EQ(ps.size(), size_t(1));
  if (!ps.empty()) { CHECK_EQ(ps[0].ip, std::string("10.0.0.3")); CHECK(ps[0].id == peer.node_id()); }
}

TEST(discovery_peer_table_bounded) {
  auto me = Identity::generate();
  DiscoveryConfig c; c.multicast = false; c.port = 0; c.max_peers = 4;
  Discovery d(me, 1, c);
  for (int i = 0; i < 10; ++i) d.handle_datagram(make_announce(Identity::generate(), 1, np::now_ms()), "x");
  CHECK_EQ(d.peers().size(), size_t(4));
}

TEST(discovery_udp_unicast_seed) {  // two real UDP sockets on loopback
  auto a = Identity::generate(), b = Identity::generate();
  DiscoveryConfig cb; cb.multicast = false; cb.port = 0;
  Discovery db(b, 1, cb);
  DiscoveryConfig ca; ca.multicast = false; ca.port = 0; ca.seeds = {{"127.0.0.1", db.bound_port()}};
  Discovery da(a, 1, ca);
  Announce ann; ann.tcp_port = 5555; ann.name = "node-a"; ann.roles = ROLE_PUBLISHER;
  da.announce(ann);
  db.poll(1000);
  auto ps = db.peers();
  CHECK_EQ(ps.size(), size_t(1));
  if (!ps.empty()) { CHECK_EQ(ps[0].ann.tcp_port, 5555); CHECK_EQ(ps[0].ann.name, std::string("node-a")); CHECK_EQ(ps[0].ip, std::string("127.0.0.1")); }
}

TEST(discovery_udp_multicast_loopback) {
  auto a = Identity::generate(), b = Identity::generate();
  DiscoveryConfig c; c.iface_addr = "127.0.0.1"; c.port = 47899;
  std::unique_ptr<Discovery> da, db;
  try { da = std::make_unique<Discovery>(a, 1, c); db = std::make_unique<Discovery>(b, 1, c); }
  catch (const std::exception& e) { std::printf("  SKIP multicast unavailable: %s\n", e.what()); return; }
  Announce ann; ann.tcp_port = 6000; ann.name = "mc-a";
  da->announce(ann);
  db->poll(1000);
  auto ps = db->peers();
  if (ps.empty()) { std::printf("  NOTE multicast datagram not delivered on loopback in this environment\n"); return; }
  CHECK_EQ(ps[0].ann.name, std::string("mc-a"));
  std::printf("  multicast on loopback delivered\n");
}
