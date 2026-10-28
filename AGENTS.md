# Coding Agent Quality Contract

This repository implements the approved architecture in `docs/architecture.md`. Before changing code, read that document and inspect the affected modules. Preserve its modular-monolith boundaries, server-side authorization model, database-enforced ACL filtering, atomic document-version activation, deterministic offline evaluation, and explicit MVP limitations.

## Required Checks

Before declaring a coding task complete:

1. Run `cd backend && ./mvnw verify` under a Java 21 runtime. This is the required gate for unit tests, PostgreSQL/pgvector integration tests, PMD, SpotBugs (the maintained FindBugs successor), and JaCoCo class/method coverage. If the host's default JDK is not 21, set `JAVA_HOME` and `PATH` to a JDK 21 installation before running Maven; PMD and architecture analysis must use the supported project runtime.
2. The JaCoCo bundle must have at least 70% class coverage and at least 70% method coverage. Do not lower thresholds to make a build pass. Add meaningful deterministic tests for new behavior; tests must not require paid LLM credentials.
3. Resolve PMD and SpotBugs findings. Do not broadly suppress rules. If a finding is a demonstrable false positive, add the narrowest supported suppression and explain it in code/configuration.
4. Add or update architecture tests when changing package/module boundaries. Architecture tests must reject dependency cycles and transport-layer classes depending on persistence implementation details.
5. Review the final diff against the relevant requirements in `docs/architecture.md` and the user's request. Check security invariants (identity is server-controlled, ACLs filter in SQL before fusion/model context, direct source/trace reads re-authorize), version/failure invariants, input validation, error behavior, and documentation claims.
6. Report exactly which checks ran and their actual outcomes. Never describe unrun checks as passed. Mention infrastructure-dependent tests skipped due to unavailable Docker explicitly.

## Design and Size Guardrails

- Keep classes focused on one responsibility. Prefer a target of at most 300 non-comment source lines per class; split by cohesive responsibility when exceeding it.
- Keep methods focused and generally below 40 source lines. Avoid deeply nested conditionals (more than 3 levels), long parameter lists (more than 5 parameters), duplicated logic, mutable shared state, broad exception swallowing, and unexplained magic values.
- These are review limits, not reasons to mechanically fragment cohesive code. When a limit is exceeded, document why the class/method remains cohesive and why extraction would make the design worse.
- Controllers must remain thin. Authorization must not be delegated to the LLM. Avoid unnecessary abstractions and dependencies.
- Use parameterized SQL. Never pass unauthorized retrieved material to fusion, generation, logs, citations, source previews, or traces.
- Evidence sufficiency and abstention behavior must be deterministic, shared between runtime chat and offline evaluation, and tested with weak-evidence hard negatives.
- Do not add or claim functionality, metrics, security guarantees, or test results that have not been implemented and verified.

## Code Documentation

- For every implementation language in this repository, document key classes, functions, modules, and scripts when their purpose or contract is not obvious from their names and signatures. Use the language's idiomatic form, such as Javadocs, Python docstrings, TypeScript/JSDoc, or concise shell comments; do not force one language's convention onto another.
- Add concise class-level and method-level Javadocs to public API endpoints and key Java service/domain classes when their purpose or contract is not obvious from their names and signatures.
- Document behavior callers need to rely on: authorization scope, input constraints, meaningful return/status and error behavior, side effects, and lifecycle/failure semantics. For records and DTOs, explain fields whose meaning or nullability is not self-evident.
- Make important architecture invariants explicit where they are enforced, such as server-controlled identity, SQL ACL filtering before fusion/model context, citation/source reauthorization, atomic version activation, and deterministic offline evaluation/abstention.
- Keep comments accurate to the implementation and approved architecture; update or remove them when behavior changes. Do not promise guarantees that code and tests do not establish.
- Avoid boilerplate that repeats a symbol name, annotations, or obvious statements. Prefer documenting why a contract matters and any non-obvious constraints; do not comment every getter, private helper, or straightforward line of code.

## Completion Review

In the final response, summarize the changes, list verification commands and outcomes, state any remaining gaps/limitations, and confirm whether the architecture review found any deviations from the approved plan.
