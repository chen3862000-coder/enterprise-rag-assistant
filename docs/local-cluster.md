# Local Cluster Control

These host-level scripts start, wait for, and stop the local Docker Compose cluster. Docker Compose remains the runtime; the scripts do not install Docker or run PostgreSQL, Ollama, or either backend as native host processes.

## Prerequisites

- Docker Desktop or Docker Engine with Compose v2.
- `bash` and `curl` on the host.
- A repository `.env` if you need non-default credentials or optional DeepSeek generation. Do not commit it.
- Network access for the first image pull and Ollama model download.

The default backend is Java. The Python backend uses `compose.python.yaml` and the same PostgreSQL/Ollama services.

## Start

From the repository root:

```bash
# Java backend, default project and port
./scripts/start-cluster.sh

# Python backend
./scripts/start-cluster.sh --backend python

# Isolated Python stack on another port
./scripts/start-cluster.sh \
  --backend python \
  --project enterprise-rag-python-local \
  --port 3001
```

The script builds by default, starts the Compose services, waits for Compose health checks and for the frontend to reach the backend API, then prints the URL. Use `--no-build` to reuse existing images during a quick restart.

Only one backend can own a Compose project. If a project already has the other backend running, the script refuses to start and asks you to stop that project first. Use a different project name and port to run both implementations side by side.

Switching backends requires a fresh demo login because Java and Python use separate session stores and cookies. The React source does not change.

## Stop

```bash
# Stop the default project; preserve PostgreSQL and Ollama data
./scripts/stop-cluster.sh

# Stop an isolated project
./scripts/stop-cluster.sh --project enterprise-rag-python-local
```

Named volumes are preserved by default. To deliberately reset local document, evaluation, and Ollama data:

```bash
./scripts/stop-cluster.sh --project enterprise-rag-python-local --volumes
```

`--volumes` is destructive and cannot be undone. It does not remove unrelated Docker projects, images, or volumes.

## Inspect Status and Logs

```bash
docker compose -p enterprise-rag-assistant ps
docker compose -p enterprise-rag-assistant logs -f --tail=200 backend

# Python project
docker compose \
  -p enterprise-rag-python-local \
  -f compose.yaml -f compose.python.yaml \
  logs -f --tail=200 backend
```

Both backends expose `GET /actuator/health`. The scripts wait for the frontend API endpoint as the user-facing readiness check.

## Make Targets

```bash
make cluster-start
make cluster-start ARGS="--backend python --project enterprise-rag-python-local --port 3001"
make cluster-stop
make cluster-stop ARGS="--project enterprise-rag-python-local"
```

The Make targets are thin wrappers around the scripts.

## Troubleshooting

- **Port already in use:** choose another `--port`, or stop the project currently using it. Check with `lsof -nP -iTCP:3000 -sTCP:LISTEN` on macOS/Linux.
- **Backend mismatch:** run `./scripts/stop-cluster.sh --project NAME` before changing the backend for that project.
- **Readiness timeout:** inspect backend and Ollama logs. The first startup may need to pull the model and can take several minutes.
- **Docker Hub unavailable:** a fresh image build may fail even when cached images are present. Report the run as infrastructure-blocked rather than successful.
- **Unexpected existing containers:** use `docker compose -p NAME ps -a` and stop only the project you intend to manage.

## Safety

- The scripts never print `.env` contents or API keys.
- Compose defaults to `CHAT_MODEL_PROVIDER=none`; generated prose is optional.
- `stop-cluster.sh` preserves volumes unless `--volumes` is explicit.
- The scripts do not run `docker system prune` or remove unrelated projects, images, or volumes.
- Browser/E2E teardown should use the same Compose project name so it cannot stop an unrelated running stack.
