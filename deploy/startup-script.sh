#!/usr/bin/env bash
#
# Tesoreria Distribuida - Equipo 18
# GCE startup script (metadata: startup-script) for a cluster node.
#
# Runs as root on first boot. It:
#   1. Installs a JRE 17 runtime (Debian 12 / bookworm).
#   2. Downloads the fat jar and the dataset from Cloud Storage.
#   3. Writes the per-role TES_* environment file consumed by NodeConfig.
#   4. Installs and starts the systemd unit (node.service).
#
# The node role (leader vs replica) is inferred from TES_LEADER_HOST:
# an EMPTY TES_LEADER_HOST means this node is the LEADER. The actual
# per-node values are read from GCE instance metadata so the SAME script
# boots all three nodes.
#
# SKELETON: structure is real; fill the TODOs with project values.
set -euo pipefail

# ---------------------------------------------------------------------------
# 0. Constants and layout
# ---------------------------------------------------------------------------
readonly APP_USER="tesoreria"
readonly APP_HOME="/opt/tesoreria"
readonly JAR_PATH="${APP_HOME}/tesoreria-distribuida.jar"
readonly DATASET_PATH="${APP_HOME}/alumnos.csv"
readonly KEYFILE_PATH="${APP_HOME}/gcs-key.json"
readonly ENV_FILE="/etc/tesoreria/node.env"
readonly UNIT_PATH="/etc/systemd/system/node.service"

readonly GCS_BUCKET="tesoreria-equipo18-29936158665"
readonly GCS_JAR_OBJECT="artifacts/tesoreria-distribuida.jar"
readonly GCS_DATASET_OBJECT="data/alumnos.csv"
readonly GCS_KEY_OBJECT="secrets/gcs-key.json"
readonly GCS_UNIT_OBJECT="deploy/node.service"

log() { echo "[startup] $*"; }

# ---------------------------------------------------------------------------
# 1. Install JRE 17 runtime
# ---------------------------------------------------------------------------
install_runtime() {
    log "Installing JRE 17 and helpers"
    export DEBIAN_FRONTEND=noninteractive
    # TODO: confirm package name on the target image (Debian 12: openjdk-17-jre-headless).
    apt-get update -y
    apt-get install -y openjdk-17-jre-headless curl ca-certificates
}

# ---------------------------------------------------------------------------
# 2. Service account / user and directory layout
# ---------------------------------------------------------------------------
prepare_user() {
    log "Preparing application user and directories"
    # TODO: create a non-login system user if it does not exist.
    id -u "${APP_USER}" >/dev/null 2>&1 || useradd --system --no-create-home --shell /usr/sbin/nologin "${APP_USER}"
    mkdir -p "${APP_HOME}" "$(dirname "${ENV_FILE}")"
}

# ---------------------------------------------------------------------------
# 3. Download artifacts (jar + dataset + GCS key) from Cloud Storage
# ---------------------------------------------------------------------------
read_metadata() {
    # Helper: read a custom instance-metadata attribute by name.
    # TODO: confirm attribute names match those set at instance creation.
    local key="$1"
    curl -s -H "Metadata-Flavor: Google" \
        "http://metadata.google.internal/computeMetadata/v1/instance/attributes/${key}" || true
}

download_artifacts() {
    log "Downloading jar, dataset, unit and GCS key from gs://${GCS_BUCKET}"
    # gsutil ships on Google Cloud public images (Debian 12). Fail clearly if the
    # chosen image lacks it rather than booting into a broken half-install.
    command -v gsutil >/dev/null 2>&1 || { log "ERROR: gsutil not found; use a Google Cloud image"; exit 1; }
    gsutil cp "gs://${GCS_BUCKET}/${GCS_JAR_OBJECT}" "${JAR_PATH}"
    gsutil cp "gs://${GCS_BUCKET}/${GCS_DATASET_OBJECT}" "${DATASET_PATH}"
    # The systemd unit ships alongside the artifacts; install_service() expects it
    # at ${APP_HOME}/node.service, so it must be fetched here.
    gsutil cp "gs://${GCS_BUCKET}/${GCS_UNIT_OBJECT}" "${APP_HOME}/node.service"
    gsutil cp "gs://${GCS_BUCKET}/${GCS_KEY_OBJECT}" "${KEYFILE_PATH}" || \
        log "WARN: GCS key not downloaded (replica nodes may not need it)"
    chown -R "${APP_USER}:${APP_USER}" "${APP_HOME}"
    chmod 600 "${KEYFILE_PATH}" 2>/dev/null || true
}

# ---------------------------------------------------------------------------
# 4. Write the per-role TES_* environment file
# ---------------------------------------------------------------------------
write_env() {
    log "Writing ${ENV_FILE}"

    # Per-node values come from instance metadata so one script fits all roles.
    # Leave TES_LEADER_HOST EMPTY on the leader; set it to the leader host on
    # each replica. NodeConfig.isLeader() == (TES_LEADER_HOST empty/absent).
    local node_id leader_host peers jwt_secret
    node_id="$(read_metadata tes-node-id)"        # TODO: e.g. "node-1"
    leader_host="$(read_metadata tes-leader-host)" # TODO: empty on leader
    peers="$(read_metadata tes-peers)"            # TODO: comma-separated host:port list
    jwt_secret="$(read_metadata tes-jwt-secret)"  # TODO: SAME value across the 3 nodes

    # NOTE: the variable name is TES_JWT_SECRET on every node even though the
    #       secret value is identical cluster-wide.
    cat > "${ENV_FILE}" <<EOF
# Generated by startup-script.sh - do not edit by hand.
TES_DATASET=${DATASET_PATH}
TES_NODE_ID=${node_id}
TES_PEERS=${peers}
TES_LEADER_HOST=${leader_host}
TES_REPL_PORT=9090
TES_JWT_SECRET=${jwt_secret}
TES_BUCKET=${GCS_BUCKET}
TES_GCS_KEYFILE=${KEYFILE_PATH}
TES_WORKERS=8
EOF
    chmod 640 "${ENV_FILE}"
}

# ---------------------------------------------------------------------------
# 5. Install systemd unit and start the node
# ---------------------------------------------------------------------------
install_service() {
    log "Installing systemd unit and starting node.service"
    # node.service was fetched by download_artifacts() into ${APP_HOME}.
    if [[ -f "${APP_HOME}/node.service" ]]; then
        cp "${APP_HOME}/node.service" "${UNIT_PATH}"
    else
        log "ERROR: node.service not present at ${APP_HOME}; cannot start node"
        exit 1
    fi
    systemctl daemon-reload
    systemctl enable node.service
    systemctl restart node.service
}

# ---------------------------------------------------------------------------
# main
# ---------------------------------------------------------------------------
main() {
    install_runtime
    prepare_user
    download_artifacts
    write_env
    install_service
    log "Node bootstrap complete"
}

main "$@"
