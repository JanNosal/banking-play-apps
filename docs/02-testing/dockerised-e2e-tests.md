[🏠 Repo](../../README.md) › [📖 Docs](../README.md) › [Testing](README.md) › **Layer 3 · Dockerised E2E**

# Layer 3 · Dockerised E2E (the real Temporal server)

> **Read this when** you must prove the *shipped* system works: the actual container images, the real
> Temporal server, real env-var config, real network and persistence.

## In one line

Testcontainers `ComposeContainer` builds the app images and starts the full `docker-compose` stack
(`temporalio/auto-setup` + Postgres + MongoDB + the three apps); the test drives the migration **only
over HTTP** and asserts the same correctness matrix as Layer 2.

## How it's implemented here

File: `e2e-tests/src/test/java/.../DockerisedStackE2EIT.java`, gated behind
`@EnabledIfSystemProperty(named = "e2e.dockerised", matches = "true")` so the default `-Pit` run uses
the fast Layer 2.

```java
static final ComposeContainer STACK = new ComposeContainer(
      new File("../docker-compose.yml"), new File("../infra/docker-compose.test.yml"))
    .withBuild(true)
    .withExposedService("temporal", 7233, Wait.forListeningPort())
    .withStartupTimeout(Duration.ofMinutes(8));
// @BeforeAll: STACK.start(); await(/actuator/health on all three apps);
// @Test (@Timeout 600s): worker auto-started the migration → poll /migration/status → assert matrix over HTTP
```

Finite, deterministic runs come from the **test override** `infra/docker-compose.test.yml` (tiny seed,
`MAX_PASSES=1`, `AUTO_START=true`) layered on `docker-compose.yml`.

## Hard prerequisites (each is a real failure mode)

| Requirement | Why | Fix / reference |
|------------|-----|------------------|
| Docker daemon API new enough for Testcontainers | else "Could not find a valid Docker environment … 1.44 too new" | upgrade Docker — [Troubleshooting §Docker API](../03-build-and-run/troubleshooting.md) |
| **No `container_name:`** in compose | `ComposeContainer` rejects it | removed from `docker-compose.yml` |
| `.dockerignore` excludes `**/target/` | else the image `COPY *-exec.jar` matches >1 jar | present in repo |
| Worker publishes a `WorkerFactory` bean | Spring Boot 4 starter doesn't | fixed in `WorkerConfig` — **only this layer catches it** |

## Why this layer is non-negotiable

Layers 1–2 build the worker by hand and never touch `WorkerConfig`, the Spring starter, `application.yml`,
or the Docker image. The `WorkerFactory` bug above was **invisible** to them and failed only here. This
is the layer that answers "does the application actually run?"

## Running it

```bash
DOCKER_BUILDKIT=0 COMPOSE_DOCKER_CLI_BUILD=0 \
  ./mvnw -Pit -pl e2e-tests -am verify -De2e.dockerised=true -Dit.test=DockerisedStackE2EIT
```
(`DOCKER_BUILDKIT=0` dodges a macOS buildx permission issue — see
[Troubleshooting](../03-build-and-run/troubleshooting.md).)

## Reuse in your own project (similar stack)

1. One `ComposeContainer` over your real compose file(s); `.withBuild(true)`.
2. A **test-only compose override** that shrinks data and bounds the run.
3. Gate on **Actuator health**, then drive **only over HTTP**.
4. Assert the **same matrix** as the in-JVM layer, but trust nothing in-process.

## See also

- [Docker & Compose](../03-build-and-run/docker-and-compose.md) — the stack it boots.
- [Troubleshooting](../03-build-and-run/troubleshooting.md) — every prerequisite failure + fix.
- [`.github/copilot-instructions.md` §5 & §11](../../.github/copilot-instructions.md) — recipe + pitfalls.
