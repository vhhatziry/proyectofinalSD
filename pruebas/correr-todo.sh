#!/usr/bin/env bash
# Runs the whole local verification suite (no cloud cost). Builds, runs the unit
# tests, the replication consistency integration test, the local cluster check and
# the endpoint hardening check. The GCS cold-recovery check runs only when
# TES_BUCKET and TES_GCS_KEYFILE are set in the environment.
set -uo pipefail
REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO"
FAT="target/tesoreria-distribuida-jar-with-dependencies.jar"
FAILED=()

# Wait (bounded, no sleep) for the node ports to be released between tests so a
# JVM still shutting down from the previous check does not collide on bind.
free_ports() {
    local i
    for i in $(seq 1 600); do
        ss -ltn 2>/dev/null | grep -qE ':(8080|8082|8083|9090) ' || return 0
    done
    return 1
}

echo "### Build"
mvn -q -DskipTests clean package 2>&1 | grep -iE 'error' || true
[ -f "$FAT" ] || { echo "build failed"; exit 1; }

echo "### Unit tests (RunTests)"
java -cp "target/test-classes:$FAT" mx.ipn.escom.tesoreria.tests.RunTests | tail -2 || FAILED+=("unit")

echo "### Replication consistency (multi-writer, per-account)"
java -cp "target/test-classes:$FAT" mx.ipn.escom.tesoreria.tests.ReplConsistency | tail -6 || FAILED+=("repl-consistency")

echo "### Local cluster (replication, fault tolerance, catch-up)"
free_ports || echo "  (warning: ports still busy)"
bash "$REPO/pruebas/cluster-local.sh" | tail -3 || FAILED+=("cluster-local")

echo "### Endpoint hardening (auth, codes)"
free_ports || echo "  (warning: ports still busy)"
bash "$REPO/pruebas/endpoints.sh" | tail -2 || FAILED+=("endpoints")

if [ -n "${TES_BUCKET:-}" ] && [ -n "${TES_GCS_KEYFILE:-}" ]; then
    echo "### GCS cold recovery"
    free_ports || echo "  (warning: ports still busy)"
    bash "$REPO/pruebas/gcs-recovery.sh" | tail -2 || FAILED+=("gcs-recovery")
else
    echo "### GCS cold recovery: SKIPPED (set TES_BUCKET + TES_GCS_KEYFILE to run)"
fi

echo "======================================"
if [ "${#FAILED[@]}" -eq 0 ]; then echo "SUITE: ALL GREEN"; else echo "SUITE: FAILED -> ${FAILED[*]}"; exit 1; fi
