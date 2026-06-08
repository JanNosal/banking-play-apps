[🏠 Repo](../../README.md) › [📖 Docs](../README.md) › [Build & Run](README.md) › **Local Development**

# Local Development

> **Read this when** you need to build the reactor, understand the Maven layout, or use the Makefile.

## In one line

A Maven multi-module reactor that imports Spring Boot, Temporal, and Testcontainers as **BOMs** (not the
Spring Boot parent), targets **Java 25**, and keeps method parameter names in bytecode for Spring MVC.

## How it's implemented here

- `pom.xml` (parent, packaging `pom`): modules `libs/product-model`, `apps/mocked-apps`,
  `apps/product-inventory-service`, `apps/migration-worker`, `e2e-tests`. Key bits:
  - **BOM imports** (`<scope>import</scope>`): `spring-boot-dependencies`, `temporal-bom`,
    `testcontainers-bom` — so the plain model lib isn't coupled to the Boot lifecycle.
  - `<maven.compiler.release>25</...>` and **`<maven.compiler.parameters>true</...>`** +
    compiler-plugin `<parameters>true</parameters>` — required because there's no
    `spring-boot-starter-parent`; without it Spring MVC can't bind `@RequestParam`/`@PathVariable`.
  - `<skipITs>true</skipITs>` by default; the **`it` profile** flips it on for the Testcontainers tests.
- `e2e-tests/pom.xml`: failsafe runs `*IT`; declares the Testcontainers test deps; sets a
  `DOCKER_API_VERSION` hint env (see [Troubleshooting](troubleshooting.md)).
- Version is pinned at **`0.0.1`** across all poms (intentional signal that this is a sandbox).

## Common commands (`Makefile`)

```bash
make build        # ./mvnw verify  — compile + unit + Layer 1 (no Docker)
make it           # ./mvnw -Pit verify  — + Layer 2 (Testcontainers Mongo)
make e2e-local    # Layer 2 against a CLI-managed Mongo on :27019 (old-Docker fallback)
make up / down    # start / stop the Docker stack
make status       # loader progress + both core counts
make reset        # down -v (wipe volumes)
```

Direct Maven (a single module with its deps): `./mvnw -ntp -pl apps/migration-worker -am compile`.

## Why / key decisions

- **BOM, not parent:** keeps `libs/product-model` framework-light (Jackson only) so it's a clean shared
  contract. The cost is you must set `-parameters` and configure surefire/failsafe yourself.
- **Java 25 + virtual threads** (see [Concurrency](../01-architecture/concurrency-and-virtual-threads.md)).

## Reuse in your own project (similar stack)

1. Prefer **BOM imports** when a module must stay framework-light; remember `-parameters`.
2. Gate Docker-dependent tests behind a **profile** (`-Pit`) and default them off.
3. Wrap the handful of real commands in a **Makefile** so humans and CI agree.

## See also

- [Docker & Compose](docker-and-compose.md) · [Troubleshooting](troubleshooting.md) ·
  [Configuration](../01-architecture/configuration.md)
