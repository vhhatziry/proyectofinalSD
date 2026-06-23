#!/usr/bin/env bash
# Local multi-node cluster check (no Cloud Storage, no cost): leader + 2 replicas
# as local JVM processes. Verifies live replication, serving with 1 and 2 replicas
# down, revive + catch-up by sequence, and a constant money invariant.
set -uo pipefail
REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JAR="$REPO/target/tesoreria-distribuida-jar-with-dependencies.jar"
[ -f "$JAR" ] || { echo "Build first: mvn -q -DskipTests package"; exit 1; }
command -v jq >/dev/null || { echo "jq required"; exit 1; }
WORK="$(mktemp -d)"; CSV="$WORK/small.csv"; SECRET="testsecret"; FAIL=0
declare -A PIDS
head -n 5000 "$REPO/material-profesor/alumnos.csv" > "$CSV"

stats() { curl -s "http://127.0.0.1:$1/api/stats" | jq -r ".$2" 2>/dev/null; }
start_node() { TES_DATASET="$CSV" TES_JWT_SECRET="$SECRET" TES_NODE_ID="$1" TES_LEADER_HOST="${3:-}" \
  TES_REPL_PORT="9090" TES_WORKERS="8" nohup java -jar "$JAR" "$2" > "$WORK/$1.log" 2>&1 & PIDS[$1]=$!; }
wait_ready() { curl --retry-connrefused --retry 60 --retry-delay 1 -sf "http://127.0.0.1:$1/api/stats" >/dev/null 2>&1; }
wait_for() { local p="$1" f="$2" t="$3" i; for i in $(seq 1 400); do [ "$(stats "$p" "$f")" = "$t" ] && return 0; done; return 1; }
chk() { if [ "$2" = "$3" ]; then echo "  PASS $1 ($2)"; else echo "  FAIL $1: $2 != $3"; FAIL=$((FAIL+1)); fi; }
cleanup() { for p in "${PIDS[@]}"; do kill "$p" 2>/dev/null; done; rm -rf "$WORK"; }
trap cleanup EXIT

start_node nodo-1 8080 ""
wait_ready 8080 || { echo "leader failed"; cat "$WORK/nodo-1.log"; exit 1; }
T0=$(stats 8080 totalBalance); chk "accountCount" "$(stats 8080 accountCount)" 5000
start_node nodo-2 8082 "127.0.0.1"; start_node nodo-3 8083 "127.0.0.1"
wait_ready 8082; wait_ready 8083
curl -s -X POST http://127.0.0.1:8080/api/register -d '{"username":"t","password":"p"}' >/dev/null
TOKEN=$(curl -s -X POST http://127.0.0.1:8080/api/login -d '{"username":"t","password":"p"}' | jq -r '.token')
tx() { local c="$1" s="$2" k; for ((k=s; k<s+c; k++)); do local a=$(((k%2400)+1)) b; b=$((a+2500));
  curl -s -o /dev/null -X POST http://127.0.0.1:8080/api/transactions/transfer \
    -H "Authorization: Bearer $TOKEN" -d "{\"sourceAccountId\":\"$a\",\"targetAccountId\":\"$b\",\"amount\":\"1.00\"}"; done; }

echo "== 100 tx, all up =="; tx 100 1
wait_for 8082 lastTxId 100; wait_for 8083 lastTxId 100
chk "replica2 live" "$(stats 8082 lastTxId)" 100; chk "replica3 live" "$(stats 8083 lastTxId)" 100
chk "invariant" "$(stats 8082 totalBalance)" "$T0"
echo "== kill replica3, 50 tx =="; kill "${PIDS[nodo-3]}"; unset 'PIDS[nodo-3]'; tx 50 200
wait_for 8082 lastTxId 150; chk "serves with 1 down" "$(stats 8080 lastTxId)" 150
echo "== kill replica2, 25 tx (leader alone) =="; kill "${PIDS[nodo-2]}"; unset 'PIDS[nodo-2]'; tx 25 300
chk "leader alone" "$(stats 8080 lastTxId)" 175
echo "== revive replica3, catch up by sequence =="; start_node nodo-3 8083 "127.0.0.1"; wait_ready 8083
wait_for 8083 lastTxId 175; chk "replica3 caught up" "$(stats 8083 lastTxId)" 175
chk "replica3 invariant" "$(stats 8083 totalBalance)" "$T0"
echo "======================================"
[ "$FAIL" -eq 0 ] && echo "CLUSTER-LOCAL: ALL PASS" || echo "CLUSTER-LOCAL: $FAIL FAILED"
exit "$FAIL"
