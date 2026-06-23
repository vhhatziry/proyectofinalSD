#!/usr/bin/env bash
# Endpoint hardening checks (no Cloud Storage): Authorization tolerance, GET
# accounts out-of-range -> 404, and the /transfer error-code matrix.
set -uo pipefail
REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JAR="$REPO/target/tesoreria-distribuida-jar-with-dependencies.jar"
[ -f "$JAR" ] || { echo "Build first: mvn -q -DskipTests package"; exit 1; }
command -v jq >/dev/null || { echo "jq required"; exit 1; }
WORK="$(mktemp -d)"; CSV="$WORK/small.csv"; FAIL=0; B="http://127.0.0.1:8080"
head -n 5000 "$REPO/material-profesor/alumnos.csv" > "$CSV"

TES_DATASET="$CSV" TES_JWT_SECRET="s" TES_NODE_ID="nodo-1" TES_WORKERS="8" \
  nohup java -jar "$JAR" 8080 > "$WORK/n.log" 2>&1 & LP=$!
trap 'kill $LP 2>/dev/null; rm -rf "$WORK"' EXIT
curl --retry-connrefused --retry 60 --retry-delay 1 -sf "$B/api/stats" >/dev/null || { echo "no start"; exit 1; }
curl -s -X POST "$B/api/register" -d '{"username":"u","password":"p"}' >/dev/null
TOK=$(curl -s -X POST "$B/api/login" -d '{"username":"u","password":"p"}' | jq -r .token)
g() { curl -s -o /dev/null -w '%{http_code}' -H "Authorization: $1" "$B$2"; }
p() { curl -s -o /dev/null -w '%{http_code}' -X POST -H "Authorization: Bearer $TOK" -d "$2" "$B$1"; }
chk() { if [ "$2" = "$3" ]; then echo "  PASS $1 -> $2"; else echo "  FAIL $1: $2 != $3"; FAIL=$((FAIL+1)); fi; }

echo "== Authorization tolerance =="
chk "Bearer"         "$(g "Bearer $TOK"  /api/accounts/1)" 200
chk "bearer lower"   "$(g "bearer $TOK"  /api/accounts/1)" 200
chk "BEARER upper"   "$(g "BEARER $TOK"  /api/accounts/1)" 200
chk "double space"   "$(g "Bearer  $TOK" /api/accounts/1)" 200
chk "no header"      "$(curl -s -o /dev/null -w '%{http_code}' "$B/api/accounts/1")" 401
chk "wrong scheme"   "$(g "Basic $TOK"   /api/accounts/1)" 401
chk "bogus token"    "$(g "Bearer xxx"   /api/accounts/1)" 401
echo "== GET accounts range =="
chk "exists"         "$(g "Bearer $TOK" /api/accounts/1)"           200
chk "nonexistent"    "$(g "Bearer $TOK" /api/accounts/99999)"       404
chk "overflow"       "$(g "Bearer $TOK" /api/accounts/99999999999)" 404
chk "negative"       "$(g "Bearer $TOK" /api/accounts/-5)"          404
chk "non-numeric"    "$(g "Bearer $TOK" /api/accounts/abc)"         400
echo "== /transfer error matrix =="
chk "valid"          "$(p /api/transactions/transfer '{"sourceAccountId":"1","targetAccountId":"2","amount":"1.00"}')" 200
chk "bad JSON"       "$(p /api/transactions/transfer '{bad')" 400
chk "missing fields" "$(p /api/transactions/transfer '{"amount":"1.00"}')" 400
chk "non-numeric src" "$(p /api/transactions/transfer '{"sourceAccountId":"abc","targetAccountId":"2","amount":"1.00"}')" 400
chk "overflow src"   "$(p /api/transactions/transfer '{"sourceAccountId":"99999999999","targetAccountId":"2","amount":"1.00"}')" 404
chk "self-transfer"  "$(p /api/transactions/transfer '{"sourceAccountId":"1","targetAccountId":"1","amount":"1.00"}')" 400
chk "amount zero"    "$(p /api/transactions/transfer '{"sourceAccountId":"1","targetAccountId":"2","amount":"0.00"}')" 400
chk "nonexistent"    "$(p /api/transactions/transfer '{"sourceAccountId":"1","targetAccountId":"99999","amount":"1.00"}')" 404
chk "insufficient"   "$(p /api/transactions/transfer '{"sourceAccountId":"1","targetAccountId":"2","amount":"99999999.00"}')" 400
echo "======================================"
[ "$FAIL" -eq 0 ] && echo "ENDPOINTS: ALL PASS" || echo "ENDPOINTS: $FAIL FAILED"
exit "$FAIL"
