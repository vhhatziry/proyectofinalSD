#!/usr/bin/env bash
#
# Tesoreria Distribuida - Equipo 18
# Stops (does NOT delete) the cluster + generator to pause compute billing while
# keeping everything ready to encender.sh again instantly. Stopped instances keep
# their disks (jar, env, systemd unit) and the leader keeps its static IP, so a
# later start needs no re-provisioning. A graceful stop also lets each replica
# write its checkpoint to Cloud Storage.
#
# To remove everything and stop ALL charges (incl. disks + static IP), use
# deploy/destruir-infra.sh instead.
set -uo pipefail

readonly ACCOUNT="martinviverosmora@gmail.com"
readonly PROJECT="project-83c85cfe-096a-4f0e-87d"
readonly ZONE="us-central1-a"
readonly NODES=(nodo-1 nodo-2 nodo-3 generador)

GC() { gcloud "$@" --account="${ACCOUNT}" --project="${PROJECT}" --quiet; }
log() { echo "[apagar] $*"; }

log "Stopping instances (graceful, so replicas checkpoint): ${NODES[*]}"
GC compute instances stop "${NODES[@]}" --zone="${ZONE}"
log "Stopped. Disks + static IP retained. Bring back with deploy/encender.sh."
