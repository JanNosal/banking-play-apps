[🏠 Repo](../../README.md) › [📖 Docs](../README.md) › [Build & Run](README.md) › **Troubleshooting**

# Troubleshooting (the fresh-machine landmines)

> **Read this when** a build or test fails on a new machine. Every issue below was actually hit and
> fixed in this repo; each lists the **symptom → cause → fix**. The same list (generalized) is in
> [`.github/copilot-instructions.md` §11](../../.github/copilot-instructions.md).

## 1. Testcontainers can't find Docker / API too new
**Symptom:** `Could not find a valid Docker environment … client version 1.44 is too new. Maximum
supported API version is 1.43`.
**Cause:** Testcontainers 2.x needs a recent daemon API; old Docker (Engine 24 = API 1.43) is too low.
Pinning `DOCKER_API_VERSION` does **not** help (bundled docker-java ignores it).
**Fix:** upgrade Docker (Engine 25+ ⇒ API 1.44; current ⇒ 1.5x); verify with `docker version` → Server
"API version". **Fallback** until then: run Layer 2 against an external Mongo —
`make e2e-local` or `-De2e.mongo.uri=mongodb://localhost:27019` — and skip Layer 3.

## 2. `ComposeContainer` rejects `container_name:`
**Symptom:** `ExceptionInInitializerError … Compose file … has 'container_name' property set … not
supported by Testcontainers`.
**Cause:** Testcontainers manages container names itself.
**Fix:** remove every `container_name:` from `docker-compose.yml` (done). CLI `docker compose up` and
inter-container DNS (service names) are unaffected.

## 3. Worker container fails to start — missing `WorkerFactory` bean
**Symptom:** `required a bean of type 'io.temporal.worker.WorkerFactory' that could not be found`.
**Cause:** under **Spring Boot 4** the Temporal Spring Boot starter publishes `WorkflowClient` but not
`WorkerFactory`.
**Fix (in `apps/migration-worker/.../config/WorkerConfig.java`):** build the factory from the client and
start it after workers register:
```java
@Bean WorkerFactory temporalWorkerFactory(WorkflowClient client) { return WorkerFactory.newInstance(client); }
@Bean SmartInitializingSingleton temporalWorkerFactoryStarter(WorkerFactory f) { return f::start; }
```
**Note:** invisible to Layers 1–2 (they build the worker by hand) — only the dockerised test catches it.

## 4. Bean-name collision
**Symptom:** `A bean with that name has already been defined … overriding is disabled`.
**Cause:** redefining a bean the starter already names (e.g. `temporalWorkflowClient`).
**Fix:** don't redefine it — derive only the missing bean from the one the starter provides (see #3).

## 5. Dockerfile `COPY` matches more than one file
**Symptom:** `When using COPY with more than one source file, the destination must be a directory`.
**Cause:** no `.dockerignore`, so host `target/` jars (multiple versions) enter the build context and
`COPY *-exec.jar` matches several.
**Fix:** add `.dockerignore` with `**/target/` (done); rely on the `exec` classifier for a unique name.

## 6. Docker buildx permission (macOS)
**Symptom:** `open ~/.docker/buildx/current: permission denied` (file owned by root).
**Fix:** prefix builds with `DOCKER_BUILDKIT=0 COMPOSE_DOCKER_CLI_BUILD=0` (legacy builder) or
`sudo chown` the file.

## 7. Controllers 400 on every request param
**Cause:** no `spring-boot-starter-parent`, so `-parameters` wasn't set and Spring MVC can't read
`@RequestParam`/`@PathVariable` names.
**Fix:** `<maven.compiler.parameters>true</...>` + compiler-plugin `<parameters>true</parameters>`
(done in `pom.xml`).

## 8. Spring Boot 4 Mongo connection silently broken
**Cause:** using the removed `spring.data.mongodb.uri`.
**Fix:** use `spring.mongodb.uri`; keep `auto-index-creation` under `spring.data.mongodb`.

## Misc

- `docker compose ps --format '{{...}}'` custom Go templates fail on some compose versions — use plain
  `docker compose ps` or `--format json`.
- In zsh, `status` is a read-only variable — name shell loop variables anything else.

## See also

- [Docker & Compose](docker-and-compose.md) · [Local development](local-development.md) ·
  [Dockerised E2E tests](../02-testing/dockerised-e2e-tests.md)
