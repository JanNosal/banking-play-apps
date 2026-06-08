[🏠 Repo](../../README.md) › [📖 Docs](../README.md) › **Build & Run**

# Build & Run

> **Read this when** you want to compile, image, launch, or debug the stack — or you hit a
> fresh-machine failure.

## In one line

A Maven multi-module reactor (Spring Boot imported as a **BOM**, not a parent) builds three Spring Boot
apps; a single multi-stage `Dockerfile` images each; one `docker-compose.yml` runs the whole stack
(Mongo + Temporal + Postgres + the three apps); a `Makefile` wraps the common commands.

## Quick start

```bash
make up        # build images + start mongo, temporal(+postgres+ui), both cores, the worker
open http://localhost:8234            # Temporal UI — watch discovery + loader
make status    # loader progress + both core counts
make down      # stop (make reset also wipes volumes)
```

## Pages in this section

1. [Local development](local-development.md) — Maven reactor, BOM layout, `-parameters`, Makefile.
2. [Docker & Compose](docker-and-compose.md) — multi-stage image, the compose stack, `.dockerignore`.
3. [Troubleshooting](troubleshooting.md) — Docker API version, `container_name`, buildx, Boot-4 beans,
   `COPY` glob, and every other landmine, each with the exact fix.

## See also

- [Configuration](../01-architecture/configuration.md) — env vars and property precedence used at launch.
- [Testing](../02-testing/README.md) — the suite you run against what you build here.
