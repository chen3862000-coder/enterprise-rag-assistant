.PHONY: up down logs seed test eval frontend-test cluster-start cluster-stop

up:
	docker compose up --build -d

down:
	docker compose down

cluster-start:
	./scripts/start-cluster.sh $(ARGS)

cluster-stop:
	./scripts/stop-cluster.sh $(ARGS)

logs:
	docker compose logs -f --tail=200

seed:
	docker compose up --build -d backend

test:
	cd backend && ./mvnw verify

eval:
	@set -eu; \
	CSRF=$$(curl -fsS -c cookies.txt http://localhost:3000/api/auth/csrf | jq -r .token); \
	curl -fsS -b cookies.txt -c cookies.txt -H 'Content-Type: application/json' -H "X-CSRF-TOKEN: $$CSRF" -d '{"username":"carol"}' http://localhost:3000/api/auth/login >/dev/null; \
	CSRF=$$(curl -fsS -b cookies.txt -c cookies.txt http://localhost:3000/api/auth/csrf | jq -r .token); \
	curl -fsS -b cookies.txt -X POST -H "X-CSRF-TOKEN: $$CSRF" http://localhost:3000/api/evaluations/run; \
	rm -f cookies.txt

frontend-test:
	cd frontend && npm test -- --run
