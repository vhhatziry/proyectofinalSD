#!/usr/bin/env bash
# Replica exact catch-up check against a REAL bucket (uses Cloud Storage; tiny,
# cleaned up). Requires the same env as gcs-recovery.sh:
#   TES_BUCKET=<bucket>  TES_GCS_KEYFILE=<path to service-account key>
# Optional for cleanup: GCLOUD_ACCOUNT, GCLOUD_PROJECT.
#
# Verifies the mandatory "resume from the exact sequence" behaviour: a leader and
# a replica run, the leader commits transfers, the replica catches up and writes a
# durable checkpoint {watermark, balances} to GCS on graceful stop. The replica is
# then restarted with the LEADER STILL DOWN, so it cannot re-fetch anything: if it
# resumes at the right sequence with the right per-account balance, that state can
# only have come from the checkpoint. Cleans checkpoint/ and journal/ at the end.
set -uo pipefail
: "${TES_BUCKET:?set TES_BUCKET}"; : "${TES_GCS_KEYFILE:?set TES_GCS_KEYFILE}"
REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JAR="$REPO/target/tesoreria-distribuida-jar-with-dependencies.jar"
[ -f "$JAR" ] || { echo "Build first: mvn -q -DskipTests package"; exit 1; }
command -v jq >/dev/null || { echo "jq required"; exit 1; }
WORK="$(mktemp -d)"; CSV="$WORK/small.csv"; SECRET="testsecret"; FAIL=0
head -n 300 "$REPO/material-profesor/alumnos.csv" > "$CSV"
GCFLAGS=(); [ -n "${GCLOUD_ACCOUNT:-}" ] && GCFLAGS+=(--account="$GCLOUD_ACCOUNT"); [ -n "${GCLOUD_PROJECT:-}" ] && GCFLAGS+=(--project="$GCLOUD_PROJECT")
gcclean() { command -v gcloud >/dev/null && gcloud storage rm --recursive \
  "gs://$TES_BUCKET/journal/" "gs://$TES_BUCKET/checkpoint/" "${GCFLAGS[@]}" 2>/dev/null; }
trap 'kill $LPID $RPID 2>/dev/null; rm -rf "$WORK"' EXIT
stats() { curl -s "http://127.0.0.1:$1/api/stats" | jq -r ".$2" 2>/dev/null; }
chk() { if [ "$2" = "$3" ]; then echo "  PASS $1 ($2)"; else echo "  FAIL $1: $2 != $3"; FAIL=$((FAIL+1)); fi; }
wait_eq() { local p="$1" f="$2" t="$3" i; for i in $(seq 1 400); do [ "$(stats "$p" "$f")" = "$t" ] && return 0; done; return 1; }

start_leader() { TES_DATASET="$CSV" TES_JWT_SECRET="$SECRET" TES_NODE_ID="nodo-1" \
  TES_BUCKET="$TES_BUCKET" TES_GCS_KEYFILE="$TES_GCS_KEYFILE" TES_WORKERS="4" \
  nohup java -jar "$JAR" 8080 > "$1" 2>&1 & LPID=$!
  curl --retry-connrefused --retry 90 --retry-delay 1 -sf http://127.0.0.1:8080/api/stats >/dev/null 2>&1; }

start_replica() { TES_DATASET="$CSV" TES_JWT_SECRET="$SECRET" TES_NODE_ID="nodo-2" \
  TES_LEADER_HOST="127.0.0.1" TES_REPL_PORT="9090" TES_BUCKET="$TES_BUCKET" \
  TES_GCS_KEYFILE="$TES_GCS_KEYFILE" TES_REPLICA_CHECKPOINT_INTERVAL_SECS="2" TES_WORKERS="4" \
  nohup java -jar "$JAR" 8081 > "$1" 2>&1 & RPID=$!
  curl --retry-connrefused --retry 90 --retry-delay 1 -sf http://127.0.0.1:8081/api/stats >/dev/null 2>&1; }

token() { local p="$1"; curl -s -X POST "http://127.0.0.1:$p/api/register" -d '{"username":"u","password":"p"}' >/dev/null
  curl -s -X POST "http://127.0.0.1:$p/api/login" -d '{"username":"u","password":"p"}' | jq -r .token; }
balance5() { curl -s -H "Authorization: Bearer $1" "http://127.0.0.1:$2/api/accounts/5" | jq -r .balance; }

gcclean
start_leader "$WORK/leader.log" || { echo "leader failed"; cat "$WORK/leader.log"; exit 1; }
start_replica "$WORK/rep1.log"   || { echo "replica failed"; cat "$WORK/rep1.log"; exit 1; }

# Commit a handful of transfers on the leader, all crediting account 5.
TOK=$(token 8080)
for k in 1 2 3 4 6 7 8 9 10; do
  curl -s -o /dev/null -X POST http://127.0.0.1:8080/api/transactions/transfer \
    -H "Authorization: Bearer $TOK" \
    -d "{\"sourceAccountId\":\"$k\",\"targetAccountId\":\"5\",\"amount\":\"1.00\"}"
done
W=$(stats 8080 lastTxId)
echo "  leader watermark W=$W"
wait_eq 8081 lastTxId "$W" || echo "  (warning: replica did not reach W in time)"
chk "replica caught up to W" "$(stats 8081 lastTxId)" "$W"

# Capture the replica's account-5 balance after catch-up.
RTOK=$(token 8081); B5=$(balance5 "$RTOK" 8081)
echo "  replica account-5 balance after catch-up: $B5"

# Graceful stop of BOTH: the replica's shutdown hook writes the checkpoint.
kill -TERM "$RPID"; wait "$RPID" 2>/dev/null
kill -TERM "$LPID"; wait "$LPID" 2>/dev/null
grep -m1 checkpoint "$WORK/rep1.log" | sed 's/^/  /'

# Restart ONLY the replica, leader still DOWN: any restored state can only come
# from the GCS checkpoint, never from a re-fetch.
start_replica "$WORK/rep2.log" || { echo "replica restart failed"; cat "$WORK/rep2.log"; exit 1; }
grep -m1 'restored' "$WORK/rep2.log" | sed 's/^/  /'
RESTORED=$(grep -c "restored .* at watermark $W" "$WORK/rep2.log")
chk "boot log shows checkpoint restore at W" "$RESTORED" "1"
chk "resumed at exact watermark (leader down)" "$(stats 8081 lastTxId)" "$W"
RTOK2=$(token 8081)
chk "restored per-account balance" "$(balance5 "$RTOK2" 8081)" "$B5"

kill -TERM "$RPID" 2>/dev/null; wait "$RPID" 2>/dev/null
gcclean && echo "  journal/ and checkpoint/ emptied"
echo "======================================"
[ "$FAIL" -eq 0 ] && echo "GCS-CHECKPOINT: ALL PASS" || echo "GCS-CHECKPOINT: $FAIL FAILED"
exit "$FAIL"
