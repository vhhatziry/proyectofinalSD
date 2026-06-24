#!/usr/bin/env bash
#
# Tesoreria Distribuida - Equipo 18
# Wipes the durable state in Cloud Storage (the leader's journal/ and the
# replicas' checkpoint/ objects) so the next start is pristine: the leader cold-
# recovers from an empty journal and serves at lastTxId 0 with the untouched CSV
# balances, and the replicas do a full catch-up. Run it before a clean demo or a
# fresh benchmark; it does NOT touch the jar, dataset or key.
set -uo pipefail

readonly ACCOUNT="martinviverosmora@gmail.com"
readonly PROJECT="project-83c85cfe-096a-4f0e-87d"
readonly BUCKET="tesoreria-equipo18-29936158665"

log() { echo "[reset] $*"; }

log "Deleting gs://${BUCKET}/journal/ and gs://${BUCKET}/checkpoint/"
gcloud storage rm --recursive \
    "gs://${BUCKET}/journal/" "gs://${BUCKET}/checkpoint/" \
    --account="${ACCOUNT}" --project="${PROJECT}" 2>/dev/null \
    || log "(nothing to delete; already clean)"
log "Durable state cleared. The next encender.sh starts at lastTxId 0."
