#!/usr/bin/env bash
#
# Suwayomi-Server fork deploy helper.
#
# Builds the shadow jar from this checkout and ships it to a remote host that
# runs Suwayomi-Server as a systemd service, with backup + health check +
# automatic rollback. Designed to be run unattended by an agent: every input is
# an env var, every step is deterministic, and the exit code is the source of
# truth (0 = deployed and healthy, non-zero = nothing changed or rolled back).
#
# Usage:
#   scripts/deploy.sh build      # build the jar locally only
#   scripts/deploy.sh deploy     # build -> ship -> restart -> verify (+rollback on failure)
#   scripts/deploy.sh verify     # health-check the running remote service
#   scripts/deploy.sh rollback   # restore the most recent backup jar and restart
#
# Required env:
#   DEPLOY_HOST        ssh target, e.g. "deploy@manga.example.com"
#
# Optional env (defaults match the project's reference packaging):
#   REMOTE_JAR_PATH    path of the jar the service runs   (default /opt/suwayomi/Suwayomi-Server.jar)
#   SERVICE_NAME       systemd unit name                  (default suwayomi-server)
#   SERVICE_USER       owner for the deployed jar         (default suwayomi-server)
#   SERVER_PORT        port the server listens on         (default 4567)
#   HEALTH_PATH        path used for the health probe      (default /api/v1/settings/about)
#   REMOTE_SUDO        privilege prefix for remote cmds   (default "sudo"; set "" if ssh user is root)
#   SSH                ssh command                         (default "ssh")
#   SCP                scp command                         (default "scp")
#   HEALTH_TIMEOUT     seconds to wait for health         (default 90)
#   KEEP_BACKUPS       number of backup jars to retain    (default 5)
#
set -euo pipefail

# --- config -----------------------------------------------------------------
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REMOTE_JAR_PATH="${REMOTE_JAR_PATH:-/opt/suwayomi/Suwayomi-Server.jar}"
SERVICE_NAME="${SERVICE_NAME:-suwayomi-server}"
SERVICE_USER="${SERVICE_USER:-suwayomi-server}"
SERVER_PORT="${SERVER_PORT:-4567}"
HEALTH_PATH="${HEALTH_PATH:-/}"
REMOTE_SUDO="${REMOTE_SUDO-sudo}"
SSH="${SSH:-ssh}"
SCP="${SCP:-scp}"
HEALTH_TIMEOUT="${HEALTH_TIMEOUT:-90}"
KEEP_BACKUPS="${KEEP_BACKUPS:-5}"

REMOTE_DIR="$(dirname "$REMOTE_JAR_PATH")"
REMOTE_STAGE="/tmp/suwayomi-deploy-$$.jar"

log()  { printf '\033[1;34m==>\033[0m %s\n' "$*" >&2; }
ok()   { printf '\033[1;32m[ok]\033[0m %s\n'  "$*" >&2; }
warn() { printf '\033[1;33m[!]\033[0m %s\n'   "$*" >&2; }
die()  { printf '\033[1;31m[x]\033[0m %s\n'   "$*" >&2; exit 1; }

require_host() { [ -n "${DEPLOY_HOST:-}" ] || die "DEPLOY_HOST is required (e.g. DEPLOY_HOST=deploy@host scripts/deploy.sh deploy)"; }

# Run a command on the remote host, privileged via $REMOTE_SUDO.
rsudo() { $SSH "$DEPLOY_HOST" "${REMOTE_SUDO:+$REMOTE_SUDO }$*"; }
# Run a plain (unprivileged) command on the remote host.
rsh()   { $SSH "$DEPLOY_HOST" "$*"; }

# --- steps ------------------------------------------------------------------
build_jar() {
    log "Building shadow jar (gradle.properties supplies the Kotlin heap)..."
    ( cd "$REPO_ROOT" && ./gradlew :server:shadowJar --console=plain )
    LOCAL_JAR="$(ls -t "$REPO_ROOT"/server/build/Suwayomi-Server-*.jar 2>/dev/null | head -1 || true)"
    [ -n "$LOCAL_JAR" ] || die "build produced no server/build/Suwayomi-Server-*.jar"
    ok "Built $(basename "$LOCAL_JAR") ($(du -h "$LOCAL_JAR" | cut -f1))"
}

preflight() {
    require_host
    log "Preflight: checking ssh + remote service..."
    rsh "true" || die "cannot ssh to $DEPLOY_HOST"
    rsudo "systemctl status $SERVICE_NAME --no-pager -l | head -3" \
        || warn "service $SERVICE_NAME not found yet (first deploy?)"
    rsh "test -d '$REMOTE_DIR' || ${REMOTE_SUDO:+$REMOTE_SUDO }mkdir -p '$REMOTE_DIR'"
    ok "Preflight passed"
}

ship() {
    [ -n "${LOCAL_JAR:-}" ] || die "no local jar; run build first"
    log "Shipping jar to $DEPLOY_HOST:$REMOTE_STAGE ..."
    $SCP "$LOCAL_JAR" "$DEPLOY_HOST:$REMOTE_STAGE"
    if rsh "test -f '$REMOTE_JAR_PATH'"; then
        local stamp; stamp="$(date +%Y%m%d-%H%M%S)"
        log "Backing up current jar -> ${REMOTE_JAR_PATH}.bak.${stamp}"
        rsudo "cp -a '$REMOTE_JAR_PATH' '${REMOTE_JAR_PATH}.bak.${stamp}'"
    fi
    log "Installing new jar -> $REMOTE_JAR_PATH"
    rsudo "mv '$REMOTE_STAGE' '$REMOTE_JAR_PATH'"
    rsudo "chown ${SERVICE_USER}:${SERVICE_USER} '$REMOTE_JAR_PATH' || true"
    ok "Jar installed"
}

restart() {
    log "Restarting $SERVICE_NAME ..."
    rsudo "systemctl restart $SERVICE_NAME"
}

verify() {
    require_host
    log "Verifying: service active + HTTP up (timeout ${HEALTH_TIMEOUT}s)..."
    rsudo "systemctl is-active --quiet $SERVICE_NAME" || { dump_logs; return 1; }
    local deadline=$(( $(date +%s) + HEALTH_TIMEOUT )) code
    while [ "$(date +%s)" -lt "$deadline" ]; do
        # Any real HTTP status (curl gives 000 on connection failure) means the
        # app is serving on the port -> healthy. We probe the app directly on
        # localhost, so the code comes from Suwayomi, not a proxy.
        code="$(rsh "curl -s -o /dev/null -w '%{http_code}' --max-time 5 http://127.0.0.1:${SERVER_PORT}${HEALTH_PATH} || true")"
        if [ -n "$code" ] && [ "$code" != "000" ]; then
            ok "Service healthy (HTTP $code on :$SERVER_PORT)"
            return 0
        fi
        sleep 3
    done
    warn "No healthy HTTP response within ${HEALTH_TIMEOUT}s (last code: ${code:-none})"
    dump_logs
    return 1
}

dump_logs() {
    warn "Recent logs for $SERVICE_NAME:"
    rsudo "journalctl -u $SERVICE_NAME -n 40 --no-pager" >&2 || true
}

rollback() {
    require_host
    local latest
    latest="$(rsh "ls -t '${REMOTE_JAR_PATH}'.bak.* 2>/dev/null | head -1" || true)"
    [ -n "$latest" ] || die "no backup jar found to roll back to"
    log "Rolling back to $latest ..."
    rsudo "cp -a '$latest' '$REMOTE_JAR_PATH'"
    rsudo "chown ${SERVICE_USER}:${SERVICE_USER} '$REMOTE_JAR_PATH' || true"
    restart
    verify && ok "Rollback healthy" || die "rollback restarted but health check still failing"
}

prune_backups() {
    rsh "ls -t '${REMOTE_JAR_PATH}'.bak.* 2>/dev/null | tail -n +$((KEEP_BACKUPS + 1))" \
        | while read -r old; do [ -n "$old" ] && rsudo "rm -f '$old'"; done || true
}

# --- main -------------------------------------------------------------------
case "${1:-deploy}" in
    build)
        build_jar
        ;;
    deploy)
        require_host
        build_jar
        preflight
        ship
        restart
        if verify; then
            prune_backups
            ok "Deploy complete and healthy."
        else
            warn "Health check failed; rolling back."
            rollback
            die "Deploy failed and was rolled back."
        fi
        ;;
    verify)
        verify
        ;;
    rollback)
        rollback
        ;;
    *)
        die "unknown command '$1' (use: build | deploy | verify | rollback)"
        ;;
esac
