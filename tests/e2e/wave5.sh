#!/usr/bin/env bash
# WAVE-5 scenario (Part 15 of the directive) with 5 node processes on ONE host over loopback TCP + UDP multicast.
#   A = publisher   B = relay (owner-enabled --relay; splices bytes, TLS runs end-to-end through it)
#   C = cache (replicates from A)   D = backup host (replicates from A)   E = client
# Proves: discovery among 5 processes, replication, multi-source download, resume, content availability after the
# original publisher leaves, SHA-256 verification, untrusted-peer refusal, operation with no external Internet.
# Does NOT prove: radio links, multi-hop over physically separate radios, energy use, service (code) failover.
set -euo pipefail
BIN=${1:?usage: wave5.sh path/to/wavenode}
T=$(mktemp -d)
declare -A PID
cleanup() { for p in "${PID[@]:-}"; do kill "$p" 2>/dev/null || true; done; wait 2>/dev/null || true; rm -rf "$T"; }
trap cleanup EXIT
DP=$((48000 + RANDOM % 10000))
D="--multicast-if 127.0.0.1 --disc-port $DP"
fail() { echo "FAIL: $*"; for f in "$T"/*.log; do echo "--- $f"; tail -20 "$f"; done; exit 1; }
step() { echo "== $*"; }
serve() {  # serve NAME [extra args]
  local n=$1; shift
  "$BIN" --data "$T/$n" serve --port 0 --name "node-$n" --announce-ms 300 "$@" $D > "$T/$n.log" 2>&1 & PID[$n]=$!
  for i in $(seq 50); do grep -q "serving on port" "$T/$n.log" && return 0; sleep 0.1; done
  fail "node $n did not start"
}

step "identities for A B C D E"
for n in a b c d e; do "$BIN" --data "$T/$n" id > "$T/$n.id"; done

step "A publishes (16 MiB + odd tail)"
head -c $((16 * 1024 * 1024 + 777)) /dev/urandom > "$T/course.bin"
CID=$("$BIN" --data "$T/a" publish "$T/course.bin" --title "Community Health Course" --category education --keywords "health first-aid")
SHA=$(sha256sum "$T/course.bin" | cut -d' ' -f1)
[ "$CID" = "$SHA" ] || fail "content id != sha256sum"
serve a; serve b --relay

step "C (cache) and D (backup) replicate from the network"
"$BIN" --data "$T/c" fetch "$CID" --discover 2 $D > "$T/c_fetch.log" 2>&1 || fail "C replicate"
"$BIN" --data "$T/d" fetch "$CID" --discover 2 $D > "$T/d_fetch.log" 2>&1 || fail "D replicate"
serve c; serve d

step "E discovers all 4 serving nodes"
"$BIN" --data "$T/e" peers --discover 2 $D > "$T/e_peers.log" 2>&1
for n in a b c d; do grep -q "$(cat "$T/$n.id")" "$T/e_peers.log" || fail "E did not discover $n"; done

step "E searches the network (results from A, C, D; B holds nothing)"
"$BIN" --data "$T/e" search-net health --discover 2 $D > "$T/e_search.log" 2>&1
[ "$(grep -c "$CID" "$T/e_search.log")" -ge 3 ] || fail "expected >=3 sources in search results"

step "R reaches A only THROUGH relay B (2 hops, end-to-end TLS)"
sleep 1  # let B learn A from discovery
BPORT=$(sed -n 's/.*serving on port \([0-9]*\).*/\1/p' "$T/b.log")
"$BIN" --data "$T/r" fetch "$CID" --route "127.0.0.1:$BPORT,$(cat "$T/a.id")" --out "$T/r_out.bin" > "$T/r_fetch.log" 2>&1 || fail "fetch via relay B"
[ "$(sha256sum "$T/r_out.bin" | cut -d' ' -f1)" = "$SHA" ] || fail "relay download hash mismatch"
grep -q "source tls+tcp:127.0.0.1:$BPORT:" "$T/r_fetch.log" || fail "data did not flow through B"
set +e; "$BIN" --data "$T/r2" fetch "$CID" --route "127.0.0.1:$("$BIN" --data "$T/c" id >/dev/null; sed -n 's/.*serving on port \([0-9]*\).*/\1/p' "$T/c.log"),$(cat "$T/a.id")" > "$T/r2_fetch.log" 2>&1; RC=$?; set -e
[ $RC -ne 0 ] && grep -q "relaying disabled" "$T/r2_fetch.log" || fail "C relayed without owner consent"

step "E downloads part of the file, then A (the original publisher) leaves"
set +e; "$BIN" --data "$T/e" fetch "$CID" --discover 2 --stop-after 20 $D > "$T/e_fetch1.log" 2>&1; RC=$?; set -e
[ $RC -eq 3 ] || fail "expected interrupted fetch"
kill "${PID[a]}"; wait "${PID[a]}" 2>/dev/null || true; unset 'PID[a]'
sleep 1

step "E resumes from the remaining replicas"
"$BIN" --data "$T/e" fetch "$CID" --discover 2 --out "$T/e_out.bin" $D > "$T/e_fetch2.log" 2>&1 || fail "resume after publisher left"
# With several sources, chunks already in flight when the stop is requested still complete (they are verified and
# kept), so the first attempt may hold slightly more than 20 chunks. Resume must reuse exactly what was fetched.
F1=$(sed -n 's/.*fetched=\([0-9]*\).*/\1/p' "$T/e_fetch1.log")
[ "$F1" -ge 20 ] || fail "first attempt fetched too few chunks ($F1)"
grep -q "already_had=$F1 " "$T/e_fetch2.log" || fail "resume did not reuse the $F1 verified chunks"
[ "$(sha256sum "$T/e_out.bin" | cut -d' ' -f1)" = "$SHA" ] || fail "hash mismatch"
NSRC=$(grep -c "  source " "$T/e_fetch2.log")
[ "$NSRC" -ge 2 ] || fail "expected multi-source download from C and D (got $NSRC sources)"

step "untrusted client refused by an allowlist node"
"$BIN" --data "$T/d" trust mode allowlist > /dev/null
kill "${PID[d]}"; wait "${PID[d]}" 2>/dev/null || true
serve d
DPORT=$(sed -n 's/.*serving on port \([0-9]*\).*/\1/p' "$T/d.log")
set +e; "$BIN" --data "$T/x" fetch "$CID" --peer 127.0.0.1:"$DPORT" > "$T/x_fetch.log" 2>&1; RC=$?; set -e
[ $RC -ne 0 ] && grep -q "not trusted" "$T/x_fetch.log" || fail "untrusted node not refused"

echo "--- measured on single-host loopback (NOT radio, NOT a benchmark):"
for f in c_fetch d_fetch r_fetch e_fetch1 e_fetch2; do echo "[$f]"; grep -E "chunks:|source |resources:" "$T/$f.log"; done
kill "${PID[b]}"; wait "${PID[b]}" 2>/dev/null || true; unset 'PID[b]'
grep "stopped" "$T/b.log" | sed 's/^/[b] /'
grep -q "relayed circuits: [1-9]" "$T/b.log" || fail "B reports no relayed circuits"
echo "PASS wave5"
