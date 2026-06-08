[🏠 Repo](../../README.md) › [📖 Docs](../README.md) › [Architecture](README.md) › **Observability & Control Plane**

# Observability & Control Plane

> **Read this when** you need to start/stop/inspect a long-running Temporal process over HTTP and watch
> it execute.

## In one line

Spring Boot Actuator exposes health/metrics on every app; a small `/migration/*` control plane on the
worker starts, stops, and reports the migration; the Temporal Web UI visualizes the workflows.

## How it's implemented here

- **Actuator** — each `application.yml` exposes `health,info,metrics` with `show-details: always`. The
  dockerised test waits on `/actuator/health` before driving the stack
  (`DockerisedStackE2EIT.up()`), and `docker-compose.yml` healthchecks gate container start order.
- **Control plane** — `apps/migration-worker/.../control`:
  - `MigrationController.java` — `POST /migration/start`, `POST /migration/stop`, `GET /migration/status`.
  - `MigrationService.java` — starts loader-then-discovery (idempotent via
    `WorkflowExecutionAlreadyStarted`); `stop()` sends `requestStop`; `status()` returns the loader's
    `@QueryMethod` `LoaderStats` snapshot (`pending`, `inFlight`, `processedCustomers`, `loadedEntries`, …).
  - `MigrationStarter.java` — an `ApplicationRunner` that auto-starts the migration when
    `migration.auto-start=true` (the Docker stack sets `AUTO_START=true`).
- **Source stats** — `mocked-apps` `/admin/stats` (+ `/admin/seed`); **target stats** —
  `product-inventory-service` `/customer-product-and-service-directory/stats`.
- **Temporal UI** — `temporal-ui` container on `http://localhost:8234`.

```bash
make status   # curls /migration/status, /admin/stats, and the target /stats in one go
```

## Why / key decisions

- **A workflow `@QueryMethod` is the progress API.** Both `make status` / the control plane *and* the
  E2E tests poll the same `LoaderStats` — observability and testability share one surface.
- **Cooperative stop over HTTP** (`/migration/stop` → `requestStop` signal) is the production way to end
  the endless loop, and exactly what the tests use.

## Reuse in your own project (similar stack)

1. Expose a workflow **`@QueryMethod` snapshot** and surface it as an HTTP `status` endpoint — reuse it
   in tests.
2. Provide **start/stop control** endpoints that map to workflow start + a stop signal.
3. Gate container start order on **Actuator health**; have integration tests wait on the same.

## See also

- [Temporal workflows](temporal-workflows.md) — the signals/queries behind the control plane.
- [Dockerised E2E tests](../02-testing/dockerised-e2e-tests.md) — health-gated startup and HTTP polling.
