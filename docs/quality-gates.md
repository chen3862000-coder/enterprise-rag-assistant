# Coding Quality Harness

The supported local quality command is `cd backend && ./mvnw verify` using Java 21. The same Maven verification runs in `.github/workflows/quality.yml` on pull requests and pushes to `main`.

## Enforced Gates

- JUnit unit tests and Testcontainers PostgreSQL/pgvector integration tests run as part of `verify`.
- PMD uses the focused ruleset in `backend/config/pmd-ruleset.xml`. It rejects classes above 300 NCSS, methods above 40 NCSS, excessive method counts, high cyclomatic/cognitive complexity, deeply nested conditionals, god classes, generic exception catches, missing control-flow braces, and `instanceof` branching inside catch blocks.
- SpotBugs runs at maximum effort and low threshold; any finding fails the build. Suppressions must be narrowly scoped and justified next to the code.
- JaCoCo rejects bundle class coverage or method coverage below 70%. Reports are generated under `backend/target/site/jacoco/`.
- ArchUnit rejects package dependency cycles and guards API/transport packages from depending on JDBC or persistence/repository implementations.

## Agent Review Contract

`AGENTS.md` is the coding agent's required repository contract. In addition to running the automated gates, the agent must compare implementation and tests to `docs/architecture.md` and the user request, inspect security/version/failure invariants, review class/method size and design, and report actual results and remaining limitations. Static analysis and architecture rules supplement human review; they do not prove security or full plan conformance.

## Current Baseline

Re-run `./mvnw clean verify` under Java 21 for the current worktree; results from earlier revisions are historical. The build uses deterministic fake embeddings and requires no paid model credentials.
