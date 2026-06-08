[🏠 Repo](../../README.md) › [📖 Docs](../README.md) › [Build & Run](README.md) › **Docker & Compose**

# Docker & Compose

> **Read this when** you need to image the apps and run the whole stack (and have it stay compatible
> with Testcontainers' `ComposeContainer`).

## In one line

One **multi-stage** `Dockerfile` (build the reactor once, copy the selected app's runnable jar) images
all three apps; one `docker-compose.yml` runs Mongo + Temporal(+Postgres+UI) + the three apps; a
`.dockerignore` keeps the build hermetic.

## How it's implemented here

- `infra/app.Dockerfile` — parameterised by `ARG APP`:
  - **build stage** (`temurin:25-jdk`): `COPY` sources, `RUN ./mvnw -DskipTests package` (compiles the
    whole reactor; layer-cached across the three images);
  - **runtime stage** (`temurin:25-jre`): `COPY --from=build /src/apps/${APP}/target/${APP}-*-exec.jar
    /app/app.jar`. The Spring Boot **`exec` classifier** makes the runnable jar uniquely named.
- `.dockerignore` — `**/target/`, `.git/`, `*.log`, `.DS_Store`. **Critical:** without it, host
  `target/` jars (possibly several versions) enter the build context and the `COPY *-exec.jar` glob
  matches more than one file and fails. See [Troubleshooting](troubleshooting.md).
- `docker-compose.yml` — services `mongo` (2 DBs), `temporal-postgresql`, `temporal`
  (`temporalio/auto-setup`), `temporal-ui` (:8234), `mocked-apps` (:8085), `product-inventory-service`
  (:8086), `migration-worker` (:8087). Healthchecks gate start order; the worker sets `AUTO_START=true`,
  `MAX_PASSES=-1`. **No `container_name:`** (Testcontainers rejects it).
- `infra/docker-compose.test.yml` — the test override (tiny seed, `MAX_PASSES=1`).
- Image tags pinned at `bank/<app>:0.0.1`.

## Run it

```bash
# Some macOS setups need the legacy builder (buildx permission); harmless everywhere:
DOCKER_BUILDKIT=0 COMPOSE_DOCKER_CLI_BUILD=0 docker compose up -d --build
open http://localhost:8234            # Temporal UI
docker compose down                   # (down -v to wipe volumes)
```

## Why / key decisions

- **Single multi-stage Dockerfile, `ARG APP`:** one reactor build, three thin runtime images.
- **No `container_name`:** required for Testcontainers `ComposeContainer`; service names still provide
  inter-container DNS and healthchecks.
- **`.dockerignore` for hermetic builds:** the image is built only from source, never host artifacts.

## Reuse in your own project (similar stack)

1. **Multi-stage** build; copy only the runnable artifact via a **unique classifier**.
2. Add a **`.dockerignore`** excluding `**/target/` (or your build dir) from day one.
3. Keep compose files **`container_name`-free** if you want Testcontainers to drive them.
4. Provide a **test compose override** for finite, deterministic E2E runs.

## See also

- [Dockerised E2E tests](../02-testing/dockerised-e2e-tests.md) — the test that boots this stack.
- [Troubleshooting](troubleshooting.md) — buildx, `COPY` glob, API version, `container_name`.
