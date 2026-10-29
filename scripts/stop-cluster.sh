#!/usr/bin/env bash
# Stop the local Compose cluster while preserving its named data volumes by default.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=cluster-lib.sh
. "$SCRIPT_DIR/cluster-lib.sh"

PROJECT="${COMPOSE_PROJECT_NAME:-$DEFAULT_PROJECT}"
REMOVE_VOLUMES=0

usage() {
  cat <<'EOF'
Usage: ./scripts/stop-cluster.sh [options]

Stop the local Compose cluster. Named PostgreSQL/Ollama volumes are preserved
unless --volumes is supplied.

Options:
  --project NAME   Compose project name (default: enterprise-rag-assistant)
  --volumes        Also delete this project's named volumes (destructive)
  -h, --help       Show this help
EOF
}

while [ "$#" -gt 0 ]; do
  case "$1" in
    --project)
      [ "$#" -ge 2 ] || die "--project requires a value"
      PROJECT="$2"
      shift 2
      ;;
    --volumes)
      REMOVE_VOLUMES=1
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

validate_project "$PROJECT"
require_runtime

if [ -z "$(project_container_ids "$PROJECT")" ]; then
  info "No local cluster containers found for project '$PROJECT'."
  exit 0
fi

compose=(docker compose -p "$PROJECT" -f "$ROOT_DIR/compose.yaml" -f "$ROOT_DIR/compose.python.yaml")
down_args=(down --remove-orphans)
if [ "$REMOVE_VOLUMES" -eq 1 ]; then
  info "WARNING: --volumes will delete PostgreSQL and Ollama data for project '$PROJECT'."
  down_args+=(--volumes)
fi

info "Stopping local cluster (project=$PROJECT)"
"${compose[@]}" "${down_args[@]}"

if [ "$REMOVE_VOLUMES" -eq 1 ]; then
  info "Cluster stopped and project volumes removed."
else
  info "Cluster stopped; named volumes preserved. Use --volumes only when you intend to reset local data."
fi
