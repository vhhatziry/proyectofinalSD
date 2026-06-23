#!/usr/bin/env bash
# Cold-recovery check against a REAL bucket (uses Cloud Storage; tiny, cleaned up).
# Requires the journal to be configured via env:
#   TES_BUCKET=<bucket>  TES_GCS_KEYFILE=<path to service-account key>
# Optional for listing/cleanup: GCLOUD_ACCOUNT, GCLOUD_PROJECT.
# Verifies: async batched journal -> graceful stop drains -> restart recovers from
# GCS and resumes at the correct sequence (no overwrite). Empties journal/ at the end.
set -uo pipefail
: "${TES_BUCKET:?set TES_BUCKET}"; : "${TES_GCS_KEYFILE:?set TES_GCS_KEYFILE}"
REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JAR="$REPO/target/tesoreria-distribuida-jar-with-dependencies.jar"
[ -f "$JAR" ] || { echo "Build first: mvn -q -DskipTests package"; exit 1; }
command -v jq >/dev/null || { echo "jq required"; exit 1; }
WORK="$(mktemp -d)"; CSV="$WORK/small.csv"; SECRET="testsecret"; FAIL=0
head -n 5000 "$REPO/material-profesor/alumnos.csv" > "$CSV"
GCFLAGS=(); [ -n "${GCLOUD_ACCOUNT:-}" ] && GCFLAGS+=(--account="$GCLOUD_ACCOUNT"); [ -n "${GCLOUD_PROJECT:-}" ] && GCFLAGS+=(--project="$GCLOUD_PROJECT")
gcclean() { command -v gcloud >/dev/null && gcloud storage rm --recursive "gs://$TES_BUCKET/journal/" "${GCFLAGS[@]}" 2>/dev/null; }
trap 'rm -rf "$WORK"' EXIT
stats() { curl -s http://127.0.0.1:8080/api/stats | jq -r ".$1" 2>/dev/null; }
chk() { if [ "$2" = "$3" ]; then echo "  PASS $1 ($2)"; else echo "  FAIL $1: $2 != $3"; FAIL=$((FAIL+1)); fi; }
wait_for() { local f="$1" t="$2" i; for i in $(seq 1 400); do [ "$(stats "$f")" = "$t" ] && return 0; done; return 1; }
start() { TES_DATASET="$CSV" TES_JWT_SECRET="$SECRET" TES_NODE_ID="nodo-1" \
  TES_BUCKET="$TES_BUCKET" TES_GCS_KEYFILE="$TES_GCS_KEYFILE" TES_WORKERS="8" \
  nohup java -jar "$JAR" 8080 > "$1" 2>&1 & LPID=$!
  curl --retry-connrefused --retry 90 --retry-delay 1 -sf http://127.0.0.1:8080/api/stats >/dev/null 2>&1; }

gcclean
start "$WORK/r1.log" || { echo "leader failed"; cat "$WORK/r1.log"; exit 1; }
chk "fresh lastTxId" "$(stats lastTxId)" 0
curl -s -X POST http://127.0.0.1:8080/api/register -d '{"username":"t","password":"p"}' >/dev/null
TOK=$(curl -s -X POST http://127.0.0.1:8080/api/login -d '{"username":"t","password":"p"}' | jq -r .token)
for k in $(seq 1 20); do curl -s -o /dev/null -X POST http://127.0.0.1:8080/api/transactions/transfer \
  -H "Authorization: Bearer $TOK" -d "{\"sourceAccountId\":\"$k\",\"targetAccountId\":\"$((k+1000))\",\"amount\":\"5.00\"}"; done
chk "lastTxId after 20" "$(stats lastTxId)" 20
wait_for gcsCount 20; chk "gcsCount durable" "$(stats gcsCount)" 20
T20=$(stats totalBalance)
kill -TERM "$LPID"; wait "$LPID" 2>/dev/null
start "$WORK/r2.log" || { echo "restart failed"; exit 1; }
grep -m1 recovered "$WORK/r2.log" | sed 's/^/  /'
chk "recovered lastTxId" "$(stats lastTxId)" 20
chk "recovered total"    "$(stats totalBalance)" "$T20"
curl -s -X POST http://127.0.0.1:8080/api/register -d '{"username":"t","password":"p"}' >/dev/null
TOK=$(curl -s -X POST http://127.0.0.1:8080/api/login -d '{"username":"t","password":"p"}' | jq -r .token)
SEQ=$(curl -s -X POST http://127.0.0.1:8080/api/transactions/transfer -H "Authorization: Bearer $TOK" \
  -d '{"sourceAccountId":"1","targetAccountId":"2","amount":"1.00"}' | jq -r .seq)
chk "next seq is 21 (no overwrite)" "$SEQ" 21
kill -TERM "$LPID"; wait "$LPID" 2>/dev/null
gcclean && echo "  journal/ emptied"
echo "======================================"
[ "$FAIL" -eq 0 ] && echo "GCS-RECOVERY: ALL PASS" || echo "GCS-RECOVERY: $FAIL FAILED"
exit "$FAIL"
