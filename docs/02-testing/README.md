[🏠 Repo](../../README.md) › [📖 Docs](../README.md) › **Testing**

# Testing Strategy — the three-layer pyramid

> **Read this when** you ask "how were the E2E tests implemented to support Temporal and MongoDB
> reliably?" Start here, then open the per-layer page. The imperative *how-to-author* companion is
> [`.github/copilot-instructions.md`](../../.github/copilot-instructions.md).

## In one line

Three test layers, cheap→expensive, each catching a class of bug the layer below **structurally
cannot** see; every wait is bounded so tests fail fast instead of hanging.

## The pyramid

| Layer | File | Real Temporal server? | Real DB? | Real HTTP? | Speed | Uniquely catches |
|------|------|----|----|----|------|------------------|
| 1 · [Workflow logic](workflow-logic-tests.md) | `MigrationWorkflowsTest` | no (in-mem test env) | no | no | ~2s | dedup, FIFO, pagination-stop, stop-signal, continue-as-new |
| 2 · [In-JVM E2E](in-jvm-e2e-tests.md) | `FullMigrationE2EIT` | no (in-mem test env) | **yes** (Testcontainers) | **yes** | ~10s | data correctness, serialization, indexes, paging, idempotency, selectivity |
| 3 · [Dockerised E2E](dockerised-e2e-tests.md) | `DockerisedStackE2EIT` | **yes** (`temporalio/auto-setup`) | yes (container) | yes | ~75s | shipped system: Spring/starter wiring, container build, env-var config, network, persistence |

**Why layer 3 is non-negotiable:** layers 1–2 build the Temporal worker *by hand* in test code, so they
never exercise the production worker bootstrap (`WorkerConfig`, the Spring Boot starter, `application.yml`,
the Docker image). A real bug — a missing `WorkerFactory` bean under Spring Boot 4 — was invisible to
layers 1–2 and failed **only** in layer 3. See [Troubleshooting §Boot-4 beans](../03-build-and-run/troubleshooting.md).

## Cross-cutting rules (apply to every layer)

See [Reliability & Temporal testing](reliability-and-temporal-testing.md): bounded waits (Awaitility +
timed `getResult` + `@Timeout`), no `Thread.sleep`, deterministic seeds, expectations derived from the
source, idempotency asserted, time-skipping OFF for real activities, full-payload equality.

## Run them

```bash
./mvnw verify                         # layer 1 (+ unit), no Docker
./mvnw -Pit verify                    # + layer 2 (needs Docker for the Mongo container)
DOCKER_BUILDKIT=0 COMPOSE_DOCKER_CLI_BUILD=0 \
  ./mvnw -Pit -pl e2e-tests -am verify -De2e.dockerised=true -Dit.test=DockerisedStackE2EIT   # layer 3
```

See [Build & Run › Troubleshooting](../03-build-and-run/troubleshooting.md) for the Docker/Testcontainers
prerequisites and fallbacks.

## Pages in this section

1. [Layer 1 · Workflow-logic tests](workflow-logic-tests.md)
2. [Layer 2 · In-JVM E2E tests](in-jvm-e2e-tests.md)
3. [Layer 3 · Dockerised E2E tests](dockerised-e2e-tests.md)
4. [Reliability & Temporal testing](reliability-and-temporal-testing.md)

## See also

- [Architecture › Temporal workflows](../01-architecture/temporal-workflows.md) — what these tests assert.
- [Architecture › Data seeding](../01-architecture/data-seeding.md) — why counts are exactly reproducible.
