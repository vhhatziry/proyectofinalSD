#!/usr/bin/env bash
#
# Tesoreria Distribuida - Equipo 18
# Build-and-publish helper: uploads the fat jar (and supporting artifacts)
# to Cloud Storage so the nodes' startup-script.sh can fetch them on boot.
#
# Run this from a workstation with the gcloud SDK authenticated against the
# project. It builds with Maven (release 17), then copies the resulting jar
# into the project bucket.
#
# SKELETON: structure is real; fill the TODOs with project values.
set -euo pipefail

# ---------------------------------------------------------------------------
# Configuration
# ---------------------------------------------------------------------------
# Resolve repo paths relative to this script (deploy/ lives under the project).
readonly SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
readonly PROJECT_DIR="$(dirname "${SCRIPT_DIR}")"

# Account/project are pinned: the bucket belongs to this project and the default
# gcloud account on a dev workstation may not have access to it.
readonly ACCOUNT="martinviverosmora@gmail.com"
readonly PROJECT="project-83c85cfe-096a-4f0e-87d"

readonly LOCAL_JAR="${PROJECT_DIR}/target/tesoreria-distribuida-jar-with-dependencies.jar"
readonly LOCAL_DATASET="${PROJECT_DIR}/material-profesor/alumnos.csv"
readonly LOCAL_KEY="${PROJECT_DIR}/credentials.json"

readonly GCS_BUCKET="tesoreria-equipo18-29936158665"
readonly GCS_JAR_OBJECT="artifacts/tesoreria-distribuida.jar"
readonly GCS_DATASET_OBJECT="data/alumnos.csv"
readonly GCS_KEY_OBJECT="secrets/gcs-key.json"
readonly GCS_DEPLOY_PREFIX="deploy"

log() { echo "[upload] $*"; }
# Pinned Cloud Storage copy so uploads use the account that owns the bucket.
gcp_cp() { gcloud storage cp "$1" "$2" --account="${ACCOUNT}" --project="${PROJECT}"; }

# ---------------------------------------------------------------------------
# 1. Build the fat jar
# ---------------------------------------------------------------------------
build_jar() {
    log "Building fat jar with Maven (release 17)"
    # The test harness is a pure-Java main (RunTests), not surefire-wired, so the
    # package build skips tests; run them separately via pruebas/correr-todo.sh.
    ( cd "${PROJECT_DIR}" && mvn -q -DskipTests clean package )
    [[ -f "${LOCAL_JAR}" ]] || { log "ERROR: jar not found at ${LOCAL_JAR}"; exit 1; }
}

# ---------------------------------------------------------------------------
# 2. Upload artifacts to Cloud Storage
# ---------------------------------------------------------------------------
upload_jar() {
    log "Uploading jar to gs://${GCS_BUCKET}/${GCS_JAR_OBJECT}"
    gcp_cp "${LOCAL_JAR}" "gs://${GCS_BUCKET}/${GCS_JAR_OBJECT}"
}

upload_dataset() {
    log "Uploading dataset to gs://${GCS_BUCKET}/${GCS_DATASET_OBJECT}"
    gcp_cp "${LOCAL_DATASET}" "gs://${GCS_BUCKET}/${GCS_DATASET_OBJECT}"
}

upload_deploy_assets() {
    log "Uploading deploy assets (node.service) for startup-script.sh"
    gcp_cp "${SCRIPT_DIR}/node.service" "gs://${GCS_BUCKET}/${GCS_DEPLOY_PREFIX}/node.service"
}

upload_key() {
    # The leader needs the service-account key for the durable GCS journal. It is
    # gitignored and lives only locally; upload it to a private object. Skipped if
    # absent (e.g. publishing only the jar/dataset for replicas).
    if [[ -f "${LOCAL_KEY}" ]]; then
        log "Uploading GCS service-account key to gs://${GCS_BUCKET}/${GCS_KEY_OBJECT}"
        gcp_cp "${LOCAL_KEY}" "gs://${GCS_BUCKET}/${GCS_KEY_OBJECT}"
    else
        log "WARN: ${LOCAL_KEY} not found; leader will have no durable journal key"
    fi
}

# ---------------------------------------------------------------------------
# main
# ---------------------------------------------------------------------------
main() {
    build_jar
    upload_jar
    upload_dataset
    upload_deploy_assets
    upload_key
    log "Artifacts published to gs://${GCS_BUCKET}"
}

main "$@"
