[🏠 Repo](../../README.md) › [📖 Docs](../README.md) › [Architecture](README.md) › **Configuration**

# Configuration

> **Read this when** you need typed, layered configuration whose **production defaults are the code
> defaults**, with env-var overrides for containers and arg overrides for tests.

## In one line

Every tunable lives in a typed `@ConfigurationProperties` **record** with `@DefaultValue`s (the
production defaults); `application.yml` maps env vars onto those; tests override only the few values that
make a run finite.

## How it's implemented here

- `apps/migration-worker/.../config/MigrationProperties.java` — `migration.*`: endpoints, `taskQueue`,
  workflow ids, page sizes, `maxConcurrency`, `maxPasses` (`-1` = endless), `continueAsNewAfter`,
  `drainAndExit`, `autoStart`, `productDirectoryReference` (default `ProductDirectory.PREMIER_BANKING`),
  HTTP timeouts.
- `apps/mocked-apps/.../seed/SeedProperties.java` — `seed.*` (see [Data seeding](data-seeding.md)).
- `application.yml` (per app) — maps env onto properties, e.g.:
  ```yaml
  migration:
    product-directory-reference: ${PRODUCT_DIRECTORY_REFERENCE:PD-PREMIER-BANKING}
    max-passes: ${MAX_PASSES:-1}
    auto-start: ${AUTO_START:false}
  ```

## The three override layers (precedence, lowest → highest)

| Layer | Mechanism | Used by |
|------|-----------|---------|
| Code defaults | `@DefaultValue` on the record | production defaults |
| YAML / **env vars** | `application.yml` `${ENV:default}` | the Docker stack (`docker-compose*.yml`) |
| **Command-line args** | `SpringApplicationBuilder(...).run("--foo=bar")` | the in-JVM E2E test |

So: layer-2 tests inject `--seed.customers=40` etc. (highest precedence); layer-3 sets `SEED_CUSTOMERS`,
`MAX_PASSES=1`, `AUTO_START=true` via the compose override; production uses the code defaults
(endless loop).

## Why / key decisions

- **Typed record + `@DefaultValue`** means the defaults are documented, compiled, and identical to what
  ships — no scattered `@Value` magic strings.
- **Env-mapping verified in layer 3 only.** A wrong env name compiles and passes layers 1–2; the
  dockerised test is the one that exercises `${ENV}` → property and would catch it.

## Reuse in your own project (similar stack)

1. One **typed properties record per app**, defaults = production defaults.
2. Map **env → property** in YAML with `${ENV:default}`; keep finite/test-only values overridable.
3. Inject test config as **command-line args** for in-JVM tests, **env** for container tests.
4. Add at least one **dockerised assertion** that an env var actually takes effect.

## See also

- [Dockerised E2E tests](../02-testing/dockerised-e2e-tests.md) — the env-mapping test override.
- [Build & Run](../03-build-and-run/README.md) — where these vars are set at launch.
