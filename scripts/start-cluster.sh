#!/usr/bin/env bash
# Start the local Compose cluster from the host and wait for the frontend API.
# Docker Compose remains the runtime; this script does not install native services.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=cluster-lib.sh
. "$SCRIPT_DIR/cluster-lib.sh"

BACKEND="${BACKEND:-$DEFAULT_BACKEND}"
PORT="${FRONTEND_PORT:-$DEFAULT_PORT}"
PROJECT="${COMPOSE_PROJECT_NAME:-$DEFAULT_PROJECT}"
TIMEOUT="${CLUSTER_TIMEOUT:-900}"
BUILD=1

usage() {
  cat <<'EOF'
Usage: ./scripts/start-cluster.sh [options]

Build and start the local Docker Compose cluster, then wait until the frontend
can reach the backend API.

Options:
  --backend java|python   Backend implementation (default: java)
  --port PORT             Host frontend port (default: 3000)
  --project NAME          Compose project name (default: enterprise-rag-assistant)
  --timeout SECONDS       Readiness timeout (default: 900)
  --no-build              Reuse existing images instead of rebuilding
  -h, --help              Show this help
EOF
}

while [ "$#" -gt 0 ]; do
  case "$1" in
    --backend)
      [ "$#" -ge 2 ] || die "--backend requires a value"
      BACKEND="$2"
      shift 2
      ;;
    --port)
      [ "$#" -ge 2 ] || die "--port requires a value"
      PORT="$2"
      shift 2
      ;;
    --project)
      [ "$#" -ge 2 ] || die "--project requires a value"
      PROJECT="$2"
      shift 2
      ;;
    --timeout)
      [ "$#" -ge 2 ] || die "--timeout requires a value"
      TIMEOUT="$2"
      shift 2
      ;;
    --no-build)
      BUILD=0
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      die "unknown option: $1 (use --help)"
      ;;
  esac
done

validate_backend "$BACKEND"
validate_port "$PORT"
validate_timeout "$TIMEOUT"
validate_project "$PROJECT"
require_runtime
[ -f "$ROOT_DIR/compose.yaml" ] || die "compose.yaml not found in $ROOT_DIR"
if [ "$BACKEND" = "python" ]; then
  [ -f "$ROOT_DIR/compose.python.yaml" ] || die "compose.python.yaml not found in $ROOT_DIR"
fi

ensure_requested_backend_is_compatible "$PROJECT" "$BACKEND"

compose=(docker compose -p "$PROJECT" -f "$ROOT_DIR/compose.yaml")
if [ "$BACKEND" = "python" ]; then
  compose+=(-f "$ROOT_DIR/compose.python.yaml")
fi
up_args=(up --detach --wait --wait-timeout "$TIMEOUT")
if [ "$BUILD" -eq 1 ]; then
  up_args+=(--build)
fi

info "Starting $BACKEND cluster (project=$PROJECT, frontend=http://127.0.0.1:$PORT)"
FRONTEND_PORT="$PORT" "${compose[@]}" "${up_args[@]}"

if ! wait_for_frontend "$PORT" "$TIMEOUT"; then
  "${compose[@]}" ps >&2 || true
  die "cluster did not become ready within ${TIMEOUT}s; inspect logs with: docker compose -p $PROJECT logs --tail=200"
fi

info "Cluster ready: http://127.0.0.1:$PORT (backend: $BACKEND, project: $PROJECT)"
info "Demo identities: alice, bob, carol"
info "Stop safely (preserves data): ./scripts/stop-cluster.sh --project $PROJECT"
