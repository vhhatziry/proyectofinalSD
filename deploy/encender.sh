#!/usr/bin/env bash
#
# Tesoreria Distribuida - Equipo 18
# Powers the dormant cluster back ON and waits until it serves. Pairs with
# apagar.sh (stop, to pause billing) and concurso.sh (the load test). The
# instances already exist (deploy/crear-infra.sh created them); this only starts
# them, so on boot each node re-runs its startup-script, re-fetches the current
# jar from Cloud Storage and restarts the service.
#
# Use this the morning of the live review: one command, wait ~1-2 min, demo.
set -uo pipefail

readonly ACCOUNT="martinviverosmora@gmail.com"
readonly PROJECT="project-83c85cfe-096a-4f0e-87d"
readonly REGION="us-central1"
readonly ZONE="us-central1-a"
readonly IP_NAME="tesoreria-leader-ip"
readonly NODES=(nodo-1 nodo-2 nodo-3 generador)

GC() { gcloud "$@" --account="${ACCOUNT}" --project="${PROJECT}" --quiet; }
log() { echo "[encender] $*"; }

log "Starting instances: ${NODES[*]}"
GC compute instances start "${NODES[@]}" --zone="${ZONE}"

LEADER_IP="$(GC compute addresses describe "${IP_NAME}" --region="${REGION}" --format='get(address)')"
log "Leader entry point: http://${LEADER_IP}:8080  (dashboard at /)"
log "Waiting for the leader to load 820k accounts and serve..."
if curl --retry-all-errors --retry-connrefused --retry 120 --retry-delay 4 --max-time 10 \
        -sf "http://${LEADER_IP}:8080/api/stats" >/dev/null 2>&1; then
    log "Leader UP. Stats:"
    curl -s "http://${LEADER_IP}:8080/api/stats"
    echo
else
    log "ERROR: leader did not come up in time; check 'journalctl -u node.service' on nodo-1"
    exit 1
fi
log "Replicas catch up from the leader in the background (see /panel)."
log "Dashboard:  http://${LEADER_IP}:8080/"
log "Load test:  bash deploy/concurso.sh"
