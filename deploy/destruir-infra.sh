#!/usr/bin/env bash
#
# Tesoreria Distribuida - Equipo 18
# Tears down everything deploy/crear-infra.sh created: the 3 instances, the
# firewall rules and the static IP. Safe to re-run; missing resources are
# skipped. Account/project are pinned (see crear-infra.sh for why).
set -uo pipefail

readonly ACCOUNT="martinviverosmora@gmail.com"
readonly PROJECT="project-83c85cfe-096a-4f0e-87d"
readonly REGION="us-central1"
readonly ZONE="us-central1-a"
readonly TAG="tesoreria"
readonly IP_NAME="tesoreria-leader-ip"

GC() { gcloud "$@" --account="${ACCOUNT}" --project="${PROJECT}" --quiet; }
log() { echo "[infra] $*"; }

main() {
    log "Deleting instances nodo-1 nodo-2 nodo-3 generador"
    GC compute instances delete nodo-1 nodo-2 nodo-3 generador --zone="${ZONE}" 2>/dev/null \
        || log "  (some instances already gone)"
    log "Deleting firewall rules"
    GC compute firewall-rules delete "${TAG}-allow-http" 2>/dev/null || log "  (${TAG}-allow-http gone)"
    GC compute firewall-rules delete "${TAG}-allow-repl" 2>/dev/null || log "  (${TAG}-allow-repl gone)"
    log "Releasing static IP ${IP_NAME}"
    GC compute addresses delete "${IP_NAME}" --region="${REGION}" 2>/dev/null || log "  (IP already released)"
    log "Teardown complete."
}

main "$@"
