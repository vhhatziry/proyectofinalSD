#!/usr/bin/env bash
#
# Tesoreria Distribuida - Equipo 18
# Provisions the 3-node cluster on Google Cloud: a static IP, firewall rules and
# exactly 2x e2-standard-2 + 1x e2-standard-4 (the E2-only constraint), each
# booting the same startup-script.sh and differing only by per-node metadata.
#
# ORDER OF OPERATIONS:
#   1. Run deploy/subir-artefactos.sh first (publishes jar + dataset + node.service
#      + GCS key to the bucket). The VMs download these on boot.
#   2. Run this script to create the IP, firewall and the 3 instances.
#   3. The leader is reachable at the printed static IP:8080 (clients + dashboard).
#   4. Run the load generator from any VM/host against that IP for the 3 scenarios.
#   5. deploy/destruir-infra.sh tears everything down.
#
# Account/project are pinned because `gcloud config set account` does NOT persist
# reliably in every shell for this project.
set -euo pipefail

# ---------------------------------------------------------------------------
# Configuration
# ---------------------------------------------------------------------------
readonly ACCOUNT="martinviverosmora@gmail.com"
readonly PROJECT="project-83c85cfe-096a-4f0e-87d"
readonly REGION="us-central1"
readonly ZONE="us-central1-a"          # one zone so short instance names resolve via internal DNS
readonly NETWORK="default"
readonly TAG="tesoreria"
readonly IMAGE_FAMILY="debian-12"
readonly IMAGE_PROJECT="debian-cloud"  # Google image: ships gsutil for the boot download
readonly SVC_ACCOUNT="tesoreria-gcs@project-83c85cfe-096a-4f0e-87d.iam.gserviceaccount.com"
readonly IP_NAME="tesoreria-leader-ip"
readonly STARTUP="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/startup-script.sh"

readonly LEADER="nodo-1"
readonly REPLICA_A="nodo-2"
readonly REPLICA_B="nodo-3"
readonly HTTP_PORT="8080"
readonly REPL_PORT="9090"

GC() { gcloud "$@" --account="${ACCOUNT}" --project="${PROJECT}" --quiet; }
log() { echo "[infra] $*"; }

# ---------------------------------------------------------------------------
# 1. Static external IP for the leader (single entry point)
# ---------------------------------------------------------------------------
reserve_ip() {
    log "Reserving static IP ${IP_NAME} in ${REGION}"
    GC compute addresses create "${IP_NAME}" --region="${REGION}" 2>/dev/null \
        || log "  (already reserved)"
    LEADER_IP="$(GC compute addresses describe "${IP_NAME}" --region="${REGION}" --format='get(address)')"
    log "  leader static IP = ${LEADER_IP}"
}

# ---------------------------------------------------------------------------
# 2. Firewall: 8080 from anywhere (clients + dashboard), 9090 between nodes
# ---------------------------------------------------------------------------
create_firewall() {
    log "Creating firewall rules (tag ${TAG})"
    GC compute firewall-rules create "${TAG}-allow-http" \
        --network="${NETWORK}" --direction=INGRESS --action=ALLOW \
        --rules="tcp:${HTTP_PORT}" --source-ranges="0.0.0.0/0" --target-tags="${TAG}" \
        2>/dev/null || log "  (${TAG}-allow-http exists)"
    GC compute firewall-rules create "${TAG}-allow-repl" \
        --network="${NETWORK}" --direction=INGRESS --action=ALLOW \
        --rules="tcp:${REPL_PORT}" --source-tags="${TAG}" --target-tags="${TAG}" \
        2>/dev/null || log "  (${TAG}-allow-repl exists)"
}

# ---------------------------------------------------------------------------
# 3. The three nodes. Replicas reach the leader by its instance name via the
#    VPC internal DNS, so no IP needs to be captured between creations.
# ---------------------------------------------------------------------------
create_node() { # name machineType leaderHost peersCsv [extraFlags...]
    local name="$1" machine="$2" leader_host="$3" peers="$4"; shift 4
    log "Creating ${name} (${machine})"
    GC compute instances create "${name}" \
        --zone="${ZONE}" --machine-type="${machine}" \
        --image-family="${IMAGE_FAMILY}" --image-project="${IMAGE_PROJECT}" \
        --network="${NETWORK}" --tags="${TAG}" \
        --service-account="${SVC_ACCOUNT}" --scopes="https://www.googleapis.com/auth/cloud-platform" \
        --metadata="^@^tes-node-id=${name}@tes-leader-host=${leader_host}@tes-peers=${peers}@tes-jwt-secret=${JWT_SECRET}" \
        --metadata-from-file="startup-script=${STARTUP}" \
        "$@"
}

create_cluster() {
    # One shared JWT secret across all three nodes (issuer is fixed in code).
    JWT_SECRET="$(openssl rand -hex 32)"
    log "Generated shared JWT secret (${#JWT_SECRET} hex chars)"

    # Leader: empty tes-leader-host marks it the leader; it gets the static IP.
    create_node "${LEADER}" "e2-standard-4" "" "${REPLICA_A}:${HTTP_PORT},${REPLICA_B}:${HTTP_PORT}" \
        --address="${LEADER_IP}"
    # Replicas: follow the leader by name; e2-standard-2 each.
    create_node "${REPLICA_A}" "e2-standard-2" "${LEADER}" "${LEADER}:${HTTP_PORT},${REPLICA_B}:${HTTP_PORT}"
    create_node "${REPLICA_B}" "e2-standard-2" "${LEADER}" "${LEADER}:${HTTP_PORT},${REPLICA_A}:${HTTP_PORT}"
}

# ---------------------------------------------------------------------------
# main
# ---------------------------------------------------------------------------
main() {
    [[ -f "${STARTUP}" ]] || { log "ERROR: ${STARTUP} not found"; exit 1; }
    reserve_ip
    create_firewall
    create_cluster
    echo
    log "Cluster created. Leader entry point: http://${LEADER_IP}:${HTTP_PORT}"
    log "  Dashboard:  http://${LEADER_IP}:${HTTP_PORT}/"
    log "  Endpoints:  /api/register /api/login /api/accounts/{id} /api/transactions/transfer"
    log "  Load test (3 scenarios = power nodes off/on, same command):"
    log "    java -cp tesoreria-distribuida.jar mx.ipn.escom.tesoreria.loadtest.LoadDriver ${LEADER_IP} ${HTTP_PORT} 60 8 1 820000 scenario-1"
    log "Tear down with deploy/destruir-infra.sh"
}

main "$@"
