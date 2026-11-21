#!/usr/bin/env bash
# End-to-end test with separate OS processes on one host (loopback TCP + UDP multicast on 127.0.0.1).
# Proves: identity, LAN discovery, remote search, chunked transfer, interruption + resume, SHA-256 match,
# untrusted-node refusal. Does NOT prove anything about Bluetooth / Wi-Fi Direct.
set -euo pipefail
BIN=${1:?usage: two_node.sh path/to/wavenode}
T=$(mktemp -d)
PIDS=()
cleanup() { for p in "${PIDS[@]:-}"; do kill "$p" 2>/dev/null || true; done; wait 2>/dev/null || true; rm -rf "$T"; }
trap cleanup EXIT
DP=$((48000 + RANDOM % 10000))
D="--multicast-if 127.0.0.1 --disc-port $DP"
fail() { echo "FAIL: $*"; for f in "$T"/*.log; do echo "--- $f"; cat "$f"; done; exit 1; }
step() { echo "== $*"; }

step "identities"
A_ID=$("$BIN" --data "$T/a" id); E_ID=$("$BIN" --data "$T/e" id)
[ ${#A_ID} -eq 64 ] || fail "bad node id"
[ "$(stat -c %a "$T/a/node.key")" = "600" ] || fail "node key not 0600"

step "publish"
head -c $((8 * 1024 * 1024 + 12345)) /dev/urandom > "$T/solar.bin"
CID=$("$BIN" --data "$T/a" publish "$T/solar.bin" --title "Solar Energy Course" --category education --keywords "solar energy")
[ "$CID" = "$(sha256sum "$T/solar.bin" | cut -d' ' -f1)" ] || fail "content id != sha256sum"
"$BIN" --data "$T/a" search solar | grep -q "$CID" || fail "local search"

step "serve A"
"$BIN" --data "$T/a" serve --port 0 --name node-a $D > "$T/a.log" 2>&1 & PIDS+=($!)
for i in $(seq 50); do grep -q "serving on port" "$T/a.log" && break; sleep 0.1; done

step "discovery"
"$BIN" --data "$T/e" peers --discover 3 $D > "$T/peers.log" 2>&1
grep -q "$A_ID" "$T/peers.log" || fail "E did not discover A"

step "remote search"
"$BIN" --data "$T/e" search-net energy --discover 3 $D > "$T/search.log" 2>&1
grep -q "$CID" "$T/search.log" || fail "remote search"

step "interrupted fetch"
set +e; "$BIN" --data "$T/e" fetch "$CID" --discover 3 --stop-after 7 $D > "$T/fetch1.log" 2>&1; RC=$?; set -e
[ $RC -eq 3 ] || fail "expected incomplete fetch (rc=$RC)"
F1=$(sed -n 's/.*fetched=\([0-9]*\).*/\1/p' "$T/fetch1.log")   # 7..10: in-flight pipelined chunks still complete
[ "$F1" -ge 7 ] && [ "$F1" -le 10 ] || fail "unexpected chunk count in first attempt ($F1)"

step "resumed fetch"
"$BIN" --data "$T/e" fetch "$CID" --discover 3 --out "$T/out.bin" $D > "$T/fetch2.log" 2>&1 || fail "resume fetch"
grep -q "already_had=$F1 " "$T/fetch2.log" || fail "resume did not reuse verified chunks"
[ "$(sha256sum "$T/out.bin" | cut -d' ' -f1)" = "$CID" ] || fail "downloaded file hash mismatch"
cmp -s "$T/out.bin" "$T/solar.bin" || fail "content differs"

step "untrusted node refused"
"$BIN" --data "$T/g" trust mode allowlist > /dev/null
G_CID=$("$BIN" --data "$T/g" publish "$T/solar.bin" --title "G copy")
"$BIN" --data "$T/g" serve --port 0 --name node-g --no-multicast --disc-port 0 > "$T/g.log" 2>&1 & PIDS+=($!)
for i in $(seq 50); do grep -q "serving on port" "$T/g.log" && break; sleep 0.1; done
GP=$(sed -n 's/.*serving on port \([0-9]*\).*/\1/p' "$T/g.log")
set +e; "$BIN" --data "$T/x" fetch "$G_CID" --peer 127.0.0.1:"$GP" > "$T/fetch3.log" 2>&1; RC=$?; set -e
[ $RC -ne 0 ] && grep -q "not trusted" "$T/fetch3.log" || fail "untrusted node was not refused"
X_ID=$("$BIN" --data "$T/x" id)
"$BIN" --data "$T/g" trust allow "$X_ID" > /dev/null
kill "${PIDS[-1]}"; wait "${PIDS[-1]}" 2>/dev/null || true
"$BIN" --data "$T/g" serve --port "$GP" --name node-g --no-multicast --disc-port 0 > "$T/g2.log" 2>&1 & PIDS+=($!)
for i in $(seq 50); do grep -q "serving on port" "$T/g2.log" && break; sleep 0.1; done
"$BIN" --data "$T/x" fetch "$G_CID" --peer 127.0.0.1:"$GP" > "$T/fetch4.log" 2>&1 || fail "allowlisted node refused"

echo "--- fetch summaries (measured on loopback, not radio):"
grep "chunks:" "$T/fetch1.log" "$T/fetch2.log" | sed "s|$T/||"
echo "PASS e2e_two_node"
