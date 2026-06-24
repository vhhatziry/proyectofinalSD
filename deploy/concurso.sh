#!/usr/bin/env bash
#
# Tesoreria Distribuida - Equipo 18
# Runs the contest load generator for the three scenarios and prints the scores
# and their sum. The generator runs ON the generator VM (low latency to the
# leader's internal IP); this script choreographs it over SSH and powers the
# replicas off between scenarios:
#
#   Scenario 1: nodo-1 + nodo-2 + nodo-3   (full cluster)
#   Scenario 2: nodo-1 + nodo-2            (one replica down)
#   Scenario 3: nodo-1                     (leader only)
#
# Each run is 80% balance reads + 20% transfers and ends by checking the money
# conservation invariant. score = transfers*4 + reads (per the contract).
# Assumes deploy/encender.sh already brought the cluster up.
#
# Usage: bash deploy/concurso.sh [seconds] [threads]   (defaults: 60 128)
set -uo pipefail

readonly ACCOUNT="martinviverosmora@gmail.com"
readonly PROJECT="project-83c85cfe-096a-4f0e-87d"
readonly REGION="us-central1"
readonly ZONE="us-central1-a"
readonly IP_NAME="tesoreria-leader-ip"
readonly SECONDS_RUN="${1:-60}"
readonly THREADS="${2:-128}"

GC() { gcloud "$@" --account="${ACCOUNT}" --project="${PROJECT}" --quiet; }
SSH_GEN() {
    gcloud compute ssh generador --zone="${ZONE}" --account="${ACCOUNT}" \
        --project="${PROJECT}" --tunnel-through-iap --command="$1" 2>/dev/null
}
log() { echo "[concurso] $*" >&2; }

LEADER_INT="$(GC compute instances describe nodo-1 --zone="${ZONE}" \
    --format='get(networkInterfaces[0].networkIP)')"
LEADER_IP="$(GC compute addresses describe "${IP_NAME}" --region="${REGION}" --format='get(address)')"

# Runs one scenario on the generator and returns its score on stdout; the human
# readable counters go to stderr.
run_scenario() {
    local label="$1" out score
    log "running ${label} (${SECONDS_RUN}s, ${THREADS} threads) against ${LEADER_INT}:8080"
    out="$(SSH_GEN "java -cp /opt/tesoreria-distribuida.jar mx.ipn.escom.tesoreria.loadtest.LoadDriver ${LEADER_INT} 8080 ${SECONDS_RUN} ${THREADS} 1 820000 ${label} 2>&1")"
    echo "${out}" | grep -E 'CONSISTENT|INCONSISTENT|successful|score' >&2
    score="$(echo "${out}" | grep -oE 'score.*: [0-9]+' | grep -oE '[0-9]+$')"
    echo "${score:-0}"
}

wait_leader() {
    curl --retry-all-errors --retry-connrefused --retry 90 --retry-delay 4 --max-time 10 \
        -sf "http://${LEADER_IP}:8080/api/stats" >/dev/null 2>&1
}

log "Ensuring full cluster is up for scenario 1"
GC compute instances start nodo-2 nodo-3 --zone="${ZONE}" >/dev/null 2>&1 || true
wait_leader || { log "leader not reachable"; exit 1; }

echo "=== SCENARIO 1: nodo-1 + nodo-2 + nodo-3 ==="
S1="$(run_scenario scenario-1)"

log "Stopping nodo-3 for scenario 2"
GC compute instances stop nodo-3 --zone="${ZONE}" >/dev/null 2>&1
echo "=== SCENARIO 2: nodo-1 + nodo-2 ==="
S2="$(run_scenario scenario-2)"

log "Stopping nodo-2 for scenario 3"
GC compute instances stop nodo-2 --zone="${ZONE}" >/dev/null 2>&1
echo "=== SCENARIO 3: nodo-1 only ==="
S3="$(run_scenario scenario-3)"

log "Restoring the full cluster (replicas resume from their checkpoints)"
GC compute instances start nodo-2 nodo-3 --zone="${ZONE}" >/dev/null 2>&1 || true

echo "======================================"
echo "scenario 1 score: ${S1}"
echo "scenario 2 score: ${S2}"
echo "scenario 3 score: ${S3}"
echo "TOTAL score:      $(( S1 + S2 + S3 ))"
