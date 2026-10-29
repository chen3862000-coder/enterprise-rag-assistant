#!/usr/bin/env bash
# Shared helpers for host-level control of the local Docker Compose cluster.

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEFAULT_PROJECT="enterprise-rag-assistant"
DEFAULT_PORT="3000"
DEFAULT_BACKEND="java"

die() {
  printf 'error: %s\n' "$*" >&2
  exit 1
}

info() {
  printf '%s\n' "$*"
}

require_command() {
  command -v "$1" >/dev/null 2>&1 || die "required command not found: $1"
}

require_runtime() {
  require_command docker
  docker compose version >/dev/null 2>&1 || die "Docker Compose v2 is required"
  require_command curl
}

validate_backend() {
  case "$1" in
    java|python) ;;
    *) die "backend must be 'java' or 'python', got: $1" ;;
  esac
}

validate_port() {
  case "$1" in
    ''|*[!0-9]*) die "port must be an integer, got: $1" ;;
  esac
  [ "$1" -ge 1 ] && [ "$1" -le 65535 ] || die "port must be between 1 and 65535"
}

validate_project() {
  [ -n "$1" ] || die "project name must not be empty"
  case "$1" in
    *[!A-Za-z0-9_.-]*) die "project name may contain only letters, numbers, '.', '_' and '-'" ;;
  esac
}

validate_timeout() {
  case "$1" in
    ''|*[!0-9]*) die "timeout must be an integer number of seconds, got: $1" ;;
  esac
  [ "$1" -ge 1 ] && [ "$1" -le 86400 ] || die "timeout must be between 1 and 86400 seconds"
}

project_container_ids() {
  local project="$1"
  docker ps -aq --filter "label=com.docker.compose.project=$project"
}

existing_backend_id() {
  local project="$1"
  local ids
  ids="$(docker ps -aq \
    --filter "label=com.docker.compose.project=$project" \
    --filter "label=com.docker.compose.service=backend")"
  [ -n "$ids" ] || return 0
  printf '%s\n' "${ids%%$'\n'*}"
}

existing_backend_config_files() {
  local container_id="$1"
  docker inspect \
    --format '{{ index .Config.Labels "com.docker.compose.project.config_files" }}' \
    "$container_id" 2>/dev/null || true
}

ensure_requested_backend_is_compatible() {
  local project="$1"
  local backend="$2"
  local container_id config_files
  container_id="$(existing_backend_id "$project")"
  [ -z "$container_id" ] && return 0

  config_files="$(existing_backend_config_files "$container_id")"
  [ -n "$config_files" ] && [ "$config_files" != "<no value>" ] ||
    die "cannot determine the running backend for project '$project'; run ./scripts/stop-cluster.sh --project $project first"

  case "$backend" in
    python)
      case "$config_files" in
        *compose.python.yaml*) ;;
        *)
          die "project '$project' already uses the Java backend; run ./scripts/stop-cluster.sh --project $project first"
          ;;
      esac
      ;;
    java)
      case "$config_files" in
        *compose.python.yaml*)
          die "project '$project' already uses the Python backend; run ./scripts/stop-cluster.sh --project $project first"
          ;;
      esac
      ;;
  esac
}

wait_for_frontend() {
  local port="$1"
  local timeout="$2"
  local deadline=$((SECONDS + timeout))
  while [ "$SECONDS" -lt "$deadline" ]; do
    if curl -fsS --max-time 5 "http://127.0.0.1:$port/api/auth/csrf" >/dev/null 2>&1; then
      return 0
    fi
    sleep 2
  done
  return 1
}
