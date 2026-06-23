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

readonly LOCAL_JAR="${PROJECT_DIR}/target/tesoreria-distribuida-jar-with-dependencies.jar"
readonly LOCAL_DATASET="${PROJECT_DIR}/material-profesor/alumnos.csv"

# TODO: point these at the project bucket (reference_gcp_proyecto_final).
readonly GCS_BUCKET="TODO-bucket-name"
readonly GCS_JAR_OBJECT="artifacts/tesoreria-distribuida.jar"
readonly GCS_DATASET_OBJECT="data/alumnos.csv"
readonly GCS_DEPLOY_PREFIX="deploy"

log() { echo "[upload] $*"; }

# ---------------------------------------------------------------------------
# 1. Build the fat jar
# ---------------------------------------------------------------------------
build_jar() {
    log "Building fat jar with Maven (release 17)"
    # TODO: use -DskipTests once the test harness is wired; keep tests for CI.
    ( cd "${PROJECT_DIR}" && mvn -q clean package )
    [[ -f "${LOCAL_JAR}" ]] || { log "ERROR: jar not found at ${LOCAL_JAR}"; exit 1; }
}

# ---------------------------------------------------------------------------
# 2. Upload artifacts to Cloud Storage
# ---------------------------------------------------------------------------
upload_jar() {
    log "Uploading jar to gs://${GCS_BUCKET}/${GCS_JAR_OBJECT}"
    gsutil cp "${LOCAL_JAR}" "gs://${GCS_BUCKET}/${GCS_JAR_OBJECT}"
}

upload_dataset() {
    log "Uploading dataset to gs://${GCS_BUCKET}/${GCS_DATASET_OBJECT}"
    # TODO: the dataset rarely changes; skip with a flag if already uploaded.
    gsutil cp "${LOCAL_DATASET}" "gs://${GCS_BUCKET}/${GCS_DATASET_OBJECT}"
}

upload_deploy_assets() {
    log "Uploading deploy assets (node.service) for startup-script.sh"
    # TODO: startup-script.sh expects node.service alongside the artifacts.
    gsutil cp "${SCRIPT_DIR}/node.service" "gs://${GCS_BUCKET}/${GCS_DEPLOY_PREFIX}/node.service"
}

# ---------------------------------------------------------------------------
# main
# ---------------------------------------------------------------------------
main() {
    build_jar
    upload_jar
    upload_dataset
    upload_deploy_assets
    log "Artifacts published to gs://${GCS_BUCKET}"
}

main "$@"
