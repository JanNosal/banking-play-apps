# Guide: Comprehensive End-to-End Testing for a Spring Boot + Temporal + MongoDB Stack

> Audience: an AI coding assistant (Copilot, Claude, etc.) asked to **write or extend end-to-end
> tests** for a service built on **Spring Boot 4 (servlet MVC) + Temporal workflows + MongoDB +
> Testcontainers + virtual threads + synchronous REST clients**. Written to be followed literally, step
> by step, by a model of any capability. Prefer copying the patterns here over inventing new ones.
>
> **This repository is the worked reference implementation.** Almost every concept below has a concrete
> realization in the code, cited as `path/to/File.java → ClassName.method()`. When in doubt, **open the
> cited file and copy its shape.** Read this guide *with the repo open*; the two together are the
> deliverable.

The example domain is a "directory migration" experiment: a Temporal worker copies a customer's
records from a **legacy core** service to a **modern core** service, selectively and idempotently.
Wherever this guide says "the unit of work" / "the records," map it to whatever your service processes.

---

## §A. Repo map (what to read, and why it matters for testing)

The system under test is three Spring Boot apps + one shared model library, plus a test module.

| Path | Role | Why a test author cares |
|------|------|--------------------------|
| `libs/product-model/.../model/DirectoryEntry.java` | The aggregate record (immutable Java `record`). | It's the **shared HTTP contract** on both apps and the worker payload. `record` ⇒ structural `equals` ⇒ you can assert full-payload parity (§6.2). |
| `libs/product-model/.../model/ProductDirectory.java` | Shared id constants (`PREMIER_BANKING`, `MORTGAGE_LOAN`, …). | The discovery **filter** and the seeder and the tests all reference the *same* constants → they never drift (§2.4). |
| `apps/mocked-apps` | **Legacy/source** service (mock). Seeds data; serves the directory API. | Source of truth the test derives expectations from (§6.1). |
| `apps/mocked-apps/.../seed/DataSeeder.java` | Deterministic data generator (fixed RNG seed). | Makes counts **exactly** reproducible → exact assertions. `SeedStats.migratedCustomers()` encodes the expected subset. |
| `apps/mocked-apps/.../seed/SeedProperties.java` | `@ConfigurationProperties("seed")` typed config. | How the test shrinks the dataset to a tiny deterministic one. |
| `apps/mocked-apps/.../persistence/DirectoryEntryDocument.java` | Mongo document; `@Indexed` denormalized keys. | The only place index correctness is exercised (§8). |
| `apps/mocked-apps/.../persistence/DirectoryEntryRepository.java` | Spring Data derived queries. | Drives the two hot paths the migration relies on (by-customer, by-filter). |
| `apps/mocked-apps/.../web/LegacyCustomerProductDirectoryController.java` | The source REST API. `X-Total-Count` header. | Pagination contract the worker + tests page against. |
| `apps/mocked-apps/.../web/AdminController.java` | `/admin/stats`, `/admin/seed`. | Test/ops introspection. |
| `apps/product-inventory-service` | **Modern/target** service. Idempotent upsert. | The thing whose final state proves correctness. |
| `apps/product-inventory-service/.../web/CustomerProductDirectoryController.java` | `POST` upsert-by-id; `/stats`. | Idempotency lives here (§6.4). |
| `apps/migration-worker` | The Temporal worker (two workflows + activities). | The system orchestration under test. |
| `apps/migration-worker/.../config/WorkerConfig.java` | Builds the `WorkerFactory`, registers workflows/activities, virtual-thread options. | **Layer-3-only** wiring — see the Spring-Boot-4 pitfall (§11.3). |
| `apps/migration-worker/.../config/MigrationProperties.java` | `@ConfigurationProperties("migration")` — every tunable + default. | Production defaults live here; tests override only a few (§9). |
| `apps/migration-worker/.../workflow/DiscoveryWorkflowImpl.java` | Producer workflow: paginated scan, dedup, `continueAsNew`. | What layer-1 control-flow tests assert (§3, §7). |
| `apps/migration-worker/.../workflow/CustomerLoaderWorkflowImpl.java` | Consumer: bounded-concurrency drain of a dedup FIFO queue, stop signal. | The trickiest logic; §3 and §7 explain how it's tested. |
| `apps/migration-worker/.../activity/LoaderActivitiesImpl.java` | Real activity: paged fetch + upsert, virtual-thread fan-out, heartbeat. | Idempotency + timeouts + heartbeats (§7, §10). |
| `apps/migration-worker/.../client/{LegacyInventoryClient,NewInventoryClient,HttpClients}.java` | Synchronous `RestClient`s with explicit timeouts; `X-Total-Count` paging. | §10 timeout rules; the `Page.hasMore()` helper the tests reuse. |
| `apps/migration-worker/.../control/MigrationService.java` | Starts loader-then-discovery; `WorkflowExecutionAlreadyStarted` handling; stop/status. | Singleton-start ordering (§7). |
| `apps/migration-worker/src/test/.../MigrationWorkflowsTest.java` | **Layer 1** test. | Copy this for control-flow tests. |
| `e2e-tests/.../FullMigrationE2EIT.java` | **Layer 2** test (in-JVM, real DB+apps). | Copy this for data-correctness tests. |
| `e2e-tests/.../DockerisedStackE2EIT.java` | **Layer 3** test (real Temporal server via compose). | Copy this for shipped-system tests. |
| `pom.xml` | BOM imports (Spring Boot, Temporal, Testcontainers), `-parameters`, `it` profile, `skipITs`. | §11.7, §9, build config. |
| `e2e-tests/pom.xml` | Failsafe `*IT`, `DOCKER_API_VERSION` env, testcontainers deps. | §11.1. |
| `infra/app.Dockerfile` | Multi-stage build; `COPY *-exec.jar`. | §11.5. |
| `docker-compose.yml` + `infra/docker-compose.test.yml` | Full stack + test override (tiny seed, `MAX_PASSES=1`). | §5. |
| `.dockerignore` | Excludes `**/target/`. | §11.5. |
| `Makefile` | `up/down/it/e2e-local/status` targets. | §12 commands. |

---

## §0. How to use this guide

1. Read **§1 (the pyramid)** and **§2 (golden rules)** — non-negotiable.
2. To add tests, follow **§13 (the recipe)**, opening each cited repo file as you go.
3. Before claiming "tests pass," run **§14 (the checklist)**.
4. On any build/Docker failure, go to **§11 (pitfalls)** + **§12 (environment)** — nearly every
   fresh-machine failure is catalogued there, with the exact fix and the repo commit that applied it.

**Golden output rule:** never report a test as passing unless you actually executed it and saw
`Tests run: N, Failures: 0, Errors: 0`. A test that compiles is not a test that passes. If a layer was
skipped (e.g. Docker too old), say so explicitly — never gloss a skip as a pass.

---

## §1. The test pyramid (three layers; each proves something the others cannot)

Build **all three**. They are cheap→expensive, fast→slow; each higher layer catches a class of bug the
layer below structurally cannot see.

| Layer | File | Runs | Real Temporal server? | Real DB? | Real HTTP? | Speed | Catches |
|------|------|------|----|----|----|------|---------|
| 1 | `MigrationWorkflowsTest` | in-JVM, `TestWorkflowEnvironment`, **mocked activities** | no (in-mem test env) | no | no | ~2s | control flow: dedup, FIFO, pagination-stop, stop-signal, continue-as-new |
| 2 | `FullMigrationE2EIT` | in-JVM, `TestWorkflowEnvironment`, **real activities + real apps + real DB** | no (in-mem test env) | yes (Testcontainers) | yes (loopback) | ~10s | data correctness end-to-end, serialization, indexes, paging, idempotency, selectivity |
| 3 | `DockerisedStackE2EIT` | full `docker-compose` via `ComposeContainer` | **yes** (`temporalio/auto-setup`) | yes (container) | yes (containers) | ~75s | **shipped** system: Spring auto-config/starter beans, container build, env-var config, network, persistence |

**Why layer 3 is mandatory and not redundant.** Layers 1–2 build the worker *by hand* in test code
(`env.newWorker(...)` + `registerWorkflowImplementationTypes(...)`). They **never exercise the
production worker bootstrap** — `WorkerConfig.java`, the Temporal Spring Boot starter, `application.yml`,
env vars, the Docker image. The real bug found in this repo (a missing `WorkerFactory` bean under Spring
Boot 4 — §11.3) was invisible to layers 1–2 and failed **only** in layer 3. If you run only 1–2 and
declare victory, you have *not* confirmed the application runs.

---

## §2. Golden rules (violating any produces flaky or false-confidence tests)

1. **Every wait is bounded** — three guards together: `Awaitility.await().atMost(...).until(cond)`, a
   timed `WorkflowStub.fromTyped(wf).getResult(30, SECONDS, Void.class)`, and a hard
   `@Timeout(...)` on the method. A test must fail fast, never hang.
   *In this repo:* `FullMigrationE2EIT.newInventoryEqualsMigratedSubsetOfLegacy()` uses all three;
   `MigrationWorkflowsTest` puts `@Timeout(30, SECONDS)` on each test as a kill-switch.
2. **Never `Thread.sleep` to await work.** Poll a real condition (a `@QueryMethod` result, a count, an
   HTTP status). *In this repo:* tests poll `loader.stats().processedCustomers()` and
   `/.../stats`, never sleep.
3. **Determinism** — seed from a fixed RNG seed so counts are exactly reproducible.
   *In this repo:* `SeedProperties.randomSeed` (default 42; tests pass `7`), consumed by
   `DataSeeder.seed()` via `new Random(props.randomSeed())`.
4. **Derive expectations from the source, not constants.** *In this repo:*
   `FullMigrationE2EIT.distinctCustomersWithParentSpec()` scans the source for who *should* migrate,
   then `fetchLegacyPackage(...)` builds the expected map; the assertion compares the target to *that*,
   so it stays correct when the seed changes.
5. **Idempotency is asserted, not assumed.** *In this repo:* the loader runs `drainAndExit=false`
   (endless, re-scanning) and the test still asserts the target count equals the expected size — proving
   re-upserts don't duplicate.
6. **Hermetic builds** — `.dockerignore` with `**/target/` so images build only from source (§11.5).
7. **For real activities, time-skipping OFF** (§7). *In this repo:*
   `TestEnvironmentOptions.newBuilder().setUseTimeskipping(false)` in `FullMigrationE2EIT.bootEverything()`.
8. **Full-payload equality, not just counts** (§6.2). *In this repo:* `DirectoryEntry` is a `record`,
   so `assertThat(migrated).isEqualTo(legacy)` compares every field.

---

## §3. Layer 1 — Workflow-logic test (mocked activities)

**Goal:** prove the workflow's *control flow* in milliseconds — no DB, no HTTP, no server.
**Copy from:** `apps/migration-worker/src/test/java/.../workflow/MigrationWorkflowsTest.java`.

How it's built there:

```java
@BeforeEach void setUp() {
  env = TestWorkflowEnvironment.newInstance();
  Worker worker = env.newWorker(TASK_QUEUE);
  worker.registerWorkflowImplementationTypes(DiscoveryWorkflowImpl.class, CustomerLoaderWorkflowImpl.class);
  discoveryActivities = mock(DiscoveryActivities.class);   // Mockito — activities are mocked
  loaderActivities   = mock(LoaderActivities.class);
  worker.registerActivitiesImplementations(discoveryActivities, loaderActivities);
  env.start();
}
@AfterEach void tearDown() { env.close(); }
```

The dedup proof (the key test, `migratesEachDistinctCustomerExactlyOnce`): stub two pages where page 2
repeats an id from page 1, and assert the loader activity ran once per **distinct** id:

```java
when(discoveryActivities.fetchCustomerPage("PD-PREMIER-BANKING", 0, 3))
    .thenReturn(new CustomerPage(List.of("c0","c1","c2"), 5, true));     // hasMore=true
when(discoveryActivities.fetchCustomerPage("PD-PREMIER-BANKING", 3, 3))
    .thenReturn(new CustomerPage(List.of("c2","c3","c4"), 5, false));    // hasMore=false ENDS the scan
when(loaderActivities.loadCustomer(anyString(), anyInt()))
    .thenAnswer(inv -> new CustomerLoadResult(inv.getArgument(0), 5, 1));
// start consumer (drain-and-exit=true so it terminates), then producer (maxPasses=1):
WorkflowClient.start(loader::run, new LoaderParams(4, 50, true, 0, null));
WorkflowClient.start(discovery::discover, new DiscoveryParams("PD-PREMIER-BANKING", 3, 1, 0, LOADER_ID, 0));
WorkflowStub.fromTyped(loader).getResult(20, TimeUnit.SECONDS, Void.class);   // bounded
verify(loaderActivities, times(5)).loadCustomer(anyString(), anyInt());        // 5 distinct, not 6
assertThat(loader.stats().processedCustomers()).isEqualTo(5);
```

Second test (`requestStopEndsTheLoaderEvenWithoutDiscovery`): start the loader **not** in drain-and-exit
mode (models the production endless loop), enqueue two ids, send `requestStop()`, and assert it
terminates — proving the cooperative-stop path that layer 2/3 rely on to end the endless loop.

**Assert at this layer:** dedup, FIFO/ordering, pagination terminates on `hasMore=false`, stop-signal
ends an endless workflow, continue-as-new preserves the pending queue.

---

## §4. Layer 2 — In-JVM integration (real apps + real DB; workflows on the test env)

**Goal:** prove data is copied **correctly and selectively** through the real services, serialization,
and DB — fast, one JVM. **Copy from:** `e2e-tests/src/test/java/.../FullMigrationE2EIT.java`.

### 4.1 Booting the topology (`@BeforeAll bootEverything()`)
- **Real MongoDB** via Testcontainers, two databases in one container:
  ```java
  mongo = new MongoDBContainer("mongo:7.0"); mongo.start();
  legacyMongoUri = mongo.getReplicaSetUrl("legacy_inventory");
  newMongoUri    = mongo.getReplicaSetUrl("new_inventory");
  ```
- **Old-Docker fallback** built in: if `-De2e.mongo.uri=...` is set, the `MongoDBContainer` is skipped
  and an external Mongo is used (the test branches on `System.getProperty("e2e.mongo.uri")`). This is
  how the suite runs when Testcontainers can't talk to Docker (§11.1).
- **Real apps on random ports**, config injected as **command-line args** (highest precedence, beats
  `application.yml`):
  ```java
  mockedCtx = new SpringApplicationBuilder(MockedAppsApplication.class).run(
      "--server.port=0", "--spring.mongodb.uri=" + legacyMongoUri,
      "--spring.data.mongodb.auto-index-creation=true",
      "--seed.enabled=true", "--seed.reset=true",
      "--seed.customers=" + CUSTOMERS, "--seed.random-seed=" + SEED);   // CUSTOMERS=40, SEED=7
  ```
- **Real workflows + real activities** on the test env, **time-skipping OFF**:
  ```java
  temporal = TestWorkflowEnvironment.newInstance(
      TestEnvironmentOptions.newBuilder().setUseTimeskipping(false).build());
  Worker worker = temporal.newWorker(TASK_QUEUE);
  worker.registerWorkflowImplementationTypes(DiscoveryWorkflowImpl.class, CustomerLoaderWorkflowImpl.class);
  worker.registerActivitiesImplementations(
      new DiscoveryActivitiesImpl(legacyClient),                       // REAL, makes real HTTP
      new LoaderActivitiesImpl(legacyClient, new NewInventoryClient(newHttp)));
  temporal.start();
  ```

### 4.2 The assertion body (derive → run → bounded-wait → parity → selectivity)
```java
Set<String> migratedCustomers = distinctCustomersWithParentSpec();          // derive from source
assertThat(migratedCustomers).as("seed must produce some Premier customers").isNotEmpty();
Map<String,Product> expected = new HashMap<>();
for (String c : migratedCustomers) expected.putAll(fetchLegacyPackage(c));

WorkflowClient.start(loader::run, new LoaderParams(8, 5, false, 0, null));   // pageSize 5 → exercise paging
WorkflowClient.start(discovery::discover, new DiscoveryParams(ProductDirectory.PREMIER_BANKING, 7, 1, 0, LOADER_ID, 0));

await("all customers processed").atMost(Duration.ofSeconds(120)).pollInterval(Duration.ofMillis(200))
    .until(() -> loader.stats().processedCustomers() >= migratedCustomers.size());
loader.requestStop();                                                        // end the endless loop
WorkflowStub.fromTyped(loader).getResult(30, TimeUnit.SECONDS, Void.class);  // bounded

assertThat(loader.stats().loadedEntries()).isEqualTo(expected.size());       // (a) count via the workflow
assertThat(newInventoryTotal()).isEqualTo(expected.size());                  // (b) count via the target API
for (String c : migratedCustomers)
    assertThat(fetchNewPackage(c)).as("package for %s", c).isEqualTo(fetchLegacyPackage(c));  // (c) PAYLOAD parity
for (int i = 0; i < CUSTOMERS; i++) {                                        // (d) SELECTIVITY
    String c = String.format("CUST-%06d", i);
    if (!migratedCustomers.contains(c))
        assertThat(newTotalForCustomer(c)).as("non-Premier %s absent", c).isZero();
}
```

> **Trap this repo hit:** the selectivity loop must build ids the *same way the seeder does*
> (`"CUST-%06d"`, matching `DataSeeder`), or it silently asserts nothing. Don't re-invent a format by
> hand — read it back from the source or use the shared constant/generator.

Pagination helpers worth copying: `fetchLegacyPackage` / `fetchNewPackage` loop on `offset/limit` until
`offset+limit >= X-Total-Count`, exactly mirroring `LegacyInventoryClient.Page.hasMore(offset, limit)`.

---

## §5. Layer 3 — Dockerised E2E (the real Temporal server)

**Goal:** prove the *shipped* system: real images, real Temporal server, real env-var config, real
network/persistence. Drive **only over HTTP**. **Copy from:** `e2e-tests/.../DockerisedStackE2EIT.java`.

```java
@EnabledIfSystemProperty(named = "e2e.dockerised", matches = "true")    // gated OFF by default
class DockerisedStackE2EIT {
  static final ComposeContainer STACK = new ComposeContainer(
        new File("../docker-compose.yml"), new File("../infra/docker-compose.test.yml"))
      .withBuild(true)                                                  // build app images from source
      .withExposedService("temporal", 7233, Wait.forListeningPort())
      .withStartupTimeout(Duration.ofMinutes(8));                       // first run compiles + builds 3 images

  @BeforeAll static void up() {
    STACK.start();
    await("apps healthy").atMost(Duration.ofMinutes(2)).pollInterval(Duration.ofSeconds(2))
        .ignoreExceptions().until(() -> health(LEGACY) && health(TARGET) && health(WORKER));  // /actuator/health
  }
  @Test @Timeout(value = 600, unit = TimeUnit.SECONDS)
  void newInventoryEqualsMigratedSubsetOfLegacy() {
    // worker container auto-started the migration (AUTO_START=true); poll /migration/status,
    // then assert the SAME matrix as layer 2 — but entirely via HTTP.
  }
}
```

Finite, deterministic runs come from the **test-only compose override**
`infra/docker-compose.test.yml`, layered on `docker-compose.yml`:
```yaml
services:
  mocked-apps:      { environment: { SEED_ENABLED: "true", SEED_RESET: "true", SEED_CUSTOMERS: "40", SEED_RANDOM_SEED: "7" } }
  migration-worker: { environment: { AUTO_START: "true", MAX_PASSES: "1" } }
```
The worker auto-starts because `MigrationStarter` (an `ApplicationRunner`) calls `service.start()` when
`migration.auto-start=true` (`MigrationProperties.autoStart`, bound from env `AUTO_START`).

**Hard pre-conditions for layer 3 to even start** (each is a real failure mode — §11):
no `container_name:` in the compose file; Docker daemon API new enough for Testcontainers; the image
`COPY` glob matches exactly one artifact (`.dockerignore` excludes host `target/`).

---

## §6. What to assert (the correctness matrix)

For a copy/transform A→B system assert all of these; for other systems keep the *categories*. Every
item below is realized in `FullMigrationE2EIT` and re-asserted over HTTP in `DockerisedStackE2EIT`.

1. **Count parity** — `count(target) == size(expected-subset)`. Two independent reads in this repo:
   the workflow's `loader.stats().loadedEntries()` **and** the target's `/stats` `entries`.
2. **Payload parity** — fetch the full record both sides, assert object equality. Works here because
   `DirectoryEntry` is an immutable `record` (structural `equals`); catches lost fields, wrong
   `@JsonProperty` mapping, dropped nulls that counts hide.
3. **Selectivity** — entities that should NOT migrate are **absent** (zero) from the target. Proves the
   filter (`productDirectoryReference == PD-PREMIER-BANKING`) actually filters; mortgage-only customers
   must not leak in. The seeder deliberately creates three profiles (Premier-only / Mortgage-only /
   both) precisely to make this assertion meaningful — see `DataSeeder` profile split.
4. **Idempotency / no drift** — after completion let the endless loop re-scan; the target count is
   unchanged. Proves "upsert by id" (`CustomerProductDirectoryController.upsertEntry` →
   `DirectoryService.upsert` → `repository.save(...)`), not "insert."
5. **Deduplication** — duplicate inputs ⇒ exactly one unit of work. Asserted via Mockito `times(5)` in
   layer 1; structurally guaranteed by the `LinkedHashSet pending` queue in `CustomerLoaderWorkflowImpl`
   and the `LinkedHashSet seen` in `DiscoveryWorkflowImpl`.
6. **Edge cases — write an explicit case for each** (extend the suite):
   - empty match set → target stays empty, no crash;
   - a "whale" (one customer with dozens of records, > one page) → paging assembles all of it
     (`DataSeeder.drawHoldingCount` already creates whales; add a focused assertion);
   - pagination boundaries: total exactly `== pageSize`, `== pageSize+1`, `== 0`;
   - a retried activity (inject a transient failure) still yields correct, non-duplicated data;
   - a malformed/partial record handled per spec (assert skipped *or* errored).

---

## §7. Testing Temporal specifically (with this repo's mechanisms)

- **Time-skipping.** Mocked activities (layer 1) may keep default time-skipping ON (timers/`sleep`
  return instantly). **Real** activities (layer 2) **must** set `setUseTimeskipping(false)` or the
  skipped clock fires activity timeouts before real HTTP returns. *Where:* `FullMigrationE2EIT.bootEverything()`.
- **Signals & queries.** Drive with `@SignalMethod`s and read progress from a `@QueryMethod`. *Where:*
  `CustomerLoaderWorkflow` declares `enqueueCustomer`, `discoveryPassComplete`, `requestStop`
  (`@SignalMethod`) and `stats()` (`@QueryMethod`); tests poll `loader.stats()` in `Awaitility`,
  never sleep.
- **Bounded concurrency without busy-wait.** `CustomerLoaderWorkflowImpl.run()` fills up to
  `maxConcurrency` slots with `Async.function(activities::loadCustomer, …)` → `Promise`s, then blocks on
  `Workflow.await(() -> stopRequested || anyCompleted(running))` and reaps completed promises. Study
  this method as the canonical "drain a queue with a cap, deterministically" pattern.
- **Continue-as-new.** When `processedCustomers >= continueAsNewAfter`, it `drainAll(running)` then
  `Workflow.continueAsNew(params.withInitialPending(new ArrayList<>(pending)))` — carrying the pending
  queue forward. Test that final totals are still correct despite mid-run compaction (layer 2 sees this
  fire repeatedly with the production-shaped settings). The producer
  `DiscoveryWorkflowImpl.discover()` similarly `continueAsNew`s for an endless re-scan unless
  `maxPasses` bounds it.
- **Activity retries + idempotency + heartbeats.** `CustomerLoaderWorkflowImpl` configures
  `ActivityOptions` with `StartToCloseTimeout(5m)`, `HeartbeatTimeout(30s)`, and a `RetryOptions`
  (initial 1s, backoff 2.0, max interval 30s, **maxAttempts 5**). Because Temporal may retry,
  `LoaderActivitiesImpl.loadCustomer()` is idempotent (target upserts by id) and calls
  `Activity.getExecutionContext().heartbeat(all.size())` so a "whale" isn't declared stuck. It also
  wraps failures with `Activity.wrap(e.getCause())` from its virtual-thread fan-out.
- **Singleton-start ordering.** `MigrationService.start()` starts the loader (fixed workflow id)
  **before** discovery, catching `WorkflowExecutionAlreadyStarted` so a second start is a no-op — so
  discovery's signals always reach a live consumer. Mirror this ordering in tests (start loader first).

---

## §8. Testing the database (MongoDB + Testcontainers)

- Versioned image (`mongo:7.0`), never `latest`.
- **Separate databases** for source/target in one container (`legacy_inventory` + `new_inventory`) so a
  bug can't make the target trivially equal the source. *Where:* `FullMigrationE2EIT` `getReplicaSetUrl`
  calls; `docker-compose.yml` uses two DB names on one `mongo` service.
- **Exercise indexes:** enable `spring.data.mongodb.auto-index-creation=true` in tests and rely on the
  same `@Indexed` fields production queries use. *Where:* `DirectoryEntryDocument` indexes `customerId`
  and `productDirectoryReference`; `DirectoryEntryRepository` derives
  `findByCustomerId`, `findByProductDirectoryReference`, `findByCustomerIdAndProductDirectoryReference`.
- **Reset per run** (`seed.reset=true` → `mongo.dropCollection(...)` in `DataSeeder.seed()`) so counts
  are exact and order-independent.
- **Spring Boot 4 gotcha:** the connection is `spring.mongodb.uri` (the old `spring.data.mongodb.uri`
  was removed); `auto-index-creation` stays under `spring.data.mongodb`. See both apps'
  `src/main/resources/application.yml`. Getting this wrong fails the connection silently — caught only
  by a real boot (layer 2/3), never by reading config.

---

## §9. Testing configuration

- Bind tunables into one typed `@ConfigurationProperties` **record** with `@DefaultValue`s, so defaults
  *are* production defaults and tests override only what makes a run finite. *Where:*
  `MigrationProperties` (`migration.*`: `maxPasses=-1`, `drainAndExit=false`, `continueAsNewAfter=500`,
  `productDirectoryReference=PD-PREMIER-BANKING`, …) and `SeedProperties` (`seed.*`).
- **Verify env-var → property mapping in layer 3**, because that path exists only in the container.
  *Where:* `apps/migration-worker/src/main/resources/application.yml` maps
  `product-directory-reference: ${PRODUCT_DIRECTORY_REFERENCE:PD-PREMIER-BANKING}`,
  `max-passes: ${MAX_PASSES:-1}`, `auto-start: ${AUTO_START:false}`; the compose files set those env
  vars. A wrong env name compiles, passes layers 1–2, and only the dockerised run catches it.
- **Precedence you rely on:** `SpringApplicationBuilder("--foo=bar")` (layer 2) beats `application.yml`;
  container env (layer 3) beats the image's baked yaml.

---

## §10. Timeouts & reliability (the exact numbers used here)

Layer them inside-out (all present in this repo):

- Activity `StartToCloseTimeout` = 5 min, `HeartbeatTimeout` = 30 s, `RetryOptions` maxAttempts 5
  (`CustomerLoaderWorkflowImpl`); discovery activity `StartToCloseTimeout` = 2 min
  (`DiscoveryWorkflowImpl`).
- HTTP client **connect 5 s, read 30 s** — always both. *Where:* `HttpClients.restClient(baseUrl,
  connectTimeout, readTimeout)`; an unbounded read would stall the whole worker. A timeout surfaces as
  a retryable exception (desired).
- `Awaitility.atMost` 60–120 s with a short `pollInterval` (200 ms–2 s).
- Timed `getResult(30, SECONDS, …)` on the final await.
- JUnit `@Timeout` absolute kill-switch: 180 s in-JVM (`FullMigrationE2EIT`), 600 s dockerised
  (`DockerisedStackE2EIT`), 30 s per layer-1 test.

If a test hangs, the bug is a missing bound at one of these layers — add it; don't inflate the others
to mask it.

---

## §11. Library & version pitfalls (the landmines — check these FIRST on a fresh machine)

Each was actually hit and fixed in this repo; the fix is in the cited file.

### 11.1 Testcontainers ↔ Docker API version
Symptom: `Could not find a valid Docker environment ... client version 1.44 is too new. Maximum
supported API version is 1.43`. **Fix: upgrade Docker** so the *server* API ≥ what Testcontainers
needs (`docker version` → Server "API version"; Engine 25+ ⇒ 1.44, current ⇒ 1.5x). Pinning
`DOCKER_API_VERSION` does **not** help (bundled docker-java ignores it; `e2e-tests/pom.xml` sets it
anyway as a hint). Until upgraded, run layer 2 against external Mongo (`-De2e.mongo.uri=...`, supported
by `FullMigrationE2EIT`) and skip layer 3.

### 11.2 `ComposeContainer` rejects `container_name:`
Symptom: `ExceptionInInitializerError ... Compose file ... has 'container_name' property set ... not
supported by Testcontainers`. **Fix: remove every `container_name:`** from `docker-compose.yml` (done
in this repo). Service names (inter-container DNS, healthchecks) are unaffected; only cosmetic
`docker ps` names change. CLI `docker compose up` still works.

### 11.3 Spring Boot major upgrade breaks framework auto-config (Temporal starter under Boot 4)
Symptom: worker container fails to start —
`required a bean of type 'io.temporal.worker.WorkerFactory' that could not be found`. The starter
publishes `WorkflowClient` but **not** `WorkerFactory` under Boot 4. **Fix (see `WorkerConfig.java`):**
```java
@Bean WorkerFactory temporalWorkerFactory(WorkflowClient client) { return WorkerFactory.newInstance(client); }
@Bean SmartInitializingSingleton temporalWorkerFactoryStarter(WorkerFactory f) { return f::start; }  // start AFTER workers registered
```
`SmartInitializingSingleton` runs after all singletons (so `migrationWorker` has registered its
workflow/activity types) and **before** any `ApplicationRunner` (so polling is live before
`MigrationStarter` kicks off the run). **Invisible to layers 1–2** — exactly why layer 3 exists.

### 11.4 Bean-name collision / override
Boot disables bean overriding by default. If you redefine a starter-named bean (the repo first tried a
second `temporalWorkflowClient`) you get `A bean with that name has already been defined ... overriding
is disabled`. **Fix:** don't redefine it — derive only what's missing (the `WorkerFactory`) from the
bean the starter already provides.

### 11.5 Dockerfile `COPY` glob matches >1 file
Symptom: `When using COPY with more than one source file, the destination must be a directory`. Cause:
no `.dockerignore`, so host `target/` (jars from several versions) enters the build context and
`infra/app.Dockerfile`'s `COPY --from=build /src/apps/${APP}/target/${APP}-*-exec.jar` matches
multiple. **Fix:** add `.dockerignore` (`**/target/`, `.git/`, `*.log`, `.DS_Store`) — present in repo.
The build also relies on the Spring Boot `exec` **classifier** so the runnable jar is uniquely named.

### 11.6 Docker buildx permission (some macOS setups)
Symptom: `open ~/.docker/buildx/current: permission denied` (file owned by root). **Fix:** prefix
builds with `DOCKER_BUILDKIT=0 COMPOSE_DOCKER_CLI_BUILD=0` (legacy builder) or `sudo chown` the file.

### 11.7 Keep `@RequestParam`/`@PathVariable` names in bytecode
The repo does **not** inherit `spring-boot-starter-parent` (it imports the BOM), so `pom.xml` sets
`<maven.compiler.parameters>true</...>` and the compiler-plugin `<parameters>true</parameters>`.
Without it, Spring MVC can't bind params and controllers 400 at runtime — a layer-3-only failure.

### 11.8 BOM-not-parent layout
`pom.xml` imports `spring-boot-dependencies`, `temporal-bom`, `testcontainers-bom` as `<scope>import</>`
rather than inheriting the Boot parent, so the plain `product-model` library isn't coupled to the Boot
lifecycle. If you copy this layout, remember §11.7 and that you must configure failsafe/surefire
yourself (see `pom.xml` `pluginManagement` and `e2e-tests/pom.xml`).

---

## §12. Environment setup & troubleshooting

```bash
docker version                       # check BOTH client and *server* "API version" (§11.1)
docker info                          # daemon reachable?
docker context ls                    # which socket; /var/run/docker.sock should resolve

# Layer 1 only (no Docker):
./mvnw verify                                                   # = make build
# Layer 1+2 (needs Docker for the Mongo container):
./mvnw -Pit verify                                              # = make it
# Layer 2 against an externally-started Mongo (old-Docker fallback):
docker run -d --rm -p 27019:27017 --name e2e-mongo mongo:7.0
./mvnw -Pit -pl e2e-tests -am verify -De2e.mongo.uri=mongodb://localhost:27019   # ≈ make e2e-local
# Layer 3 (real Temporal server; legacy builder dodges the buildx issue §11.6):
DOCKER_BUILDKIT=0 COMPOSE_DOCKER_CLI_BUILD=0 \
  ./mvnw -Pit -pl e2e-tests -am verify -De2e.dockerised=true -Dit.test=DockerisedStackE2EIT

# Most robust real-stack check without Testcontainers (drive over HTTP, like the Makefile `status`):
DOCKER_BUILDKIT=0 COMPOSE_DOCKER_CLI_BUILD=0 docker compose up -d --build
curl -s localhost:8087/migration/status
curl -s localhost:8086/customer-product-and-service-directory/stats
docker compose down
```
Tips: `docker compose ps --format '{{...}}'` custom Go templates fail on some compose versions — use
plain `docker compose ps` or `--format json`. In zsh, `status` is a read-only variable — name shell
loop variables anything else. The `Makefile` wraps the common commands (`build`, `it`, `e2e-local`,
`status`, `up`, `down`).

---

## §13. The recipe (author a new E2E suite, in order)

1. **Make data deterministic.** Add/confirm a fixed-seed generator + typed config record. *Model:*
   `DataSeeder` + `SeedProperties`; expose dataset size and `reset` as properties/env.
2. **Layer 1** (`MigrationWorkflowsTest` shape): mocked activities, assert dedup + pagination-stop +
   stop-signal. Run it green.
3. **Layer 2** (`FullMigrationE2EIT` shape): real apps + Testcontainers DB + real activities on a
   `setUseTimeskipping(false)` env; derive the expected subset; assert count + **payload** parity +
   selectivity + idempotency; add the external-Mongo fallback. Run it green.
4. **Add `.dockerignore`** (`**/target/`); confirm the app image builds from source.
5. **Layer 3** (`DockerisedStackE2EIT` shape): a test-only compose override (tiny seed, `MAX_PASSES=1`,
   `AUTO_START=true`); `ComposeContainer` gated behind `-De2e.dockerised=true`; drive over HTTP; assert
   the same matrix; ensure **no `container_name:`** in compose. Run it green.
6. **Add edge-case tests** (§6.6): empty, whale, pagination boundaries, retry, malformed.
7. **Run the full suite + the checklist (§14).** Only then report results — quoting the actual
   `Tests run:` lines, and stating honestly which layers ran vs skipped.

---

## §14. Pre-flight checklist (run before claiming success)

- [ ] All three layers exist and **each was executed and printed `Failures: 0, Errors: 0`**.
- [ ] No `Thread.sleep` to await work; every wait has `Awaitility` + timed `getResult` + `@Timeout`.
- [ ] Expectations **derived from the seeded source**, not hard-coded magic numbers.
- [ ] Full-**payload** equality asserted, not just counts.
- [ ] **Selectivity** asserted (non-matching entities absent from target).
- [ ] **Idempotency** asserted (re-scan / re-run does not change target count).
- [ ] At least one **edge case** beyond the happy path (empty, whale, pagination boundary, retry).
- [ ] Real activities run with **time-skipping OFF**; mocked-activity layer may keep it on.
- [ ] HTTP clients have **connect + read timeouts**; activities have StartToClose + Heartbeat + Retry.
- [ ] `.dockerignore` excludes `**/target/`; image `COPY` matches exactly one artifact.
- [ ] Compose file has **no `container_name:`**; layer 3 builds and boots the **real** server.
- [ ] `docker version` server API satisfies Testcontainers; if not, documented fallback used and each
      layer's status (ran / skipped) is **reported honestly**, never glossed as "passed."

---

_Distilled from getting all three layers green on a real machine: upgrading Docker (§11.1), removing
`container_name` (§11.2), adding a `.dockerignore` (§11.5), and fixing a Spring Boot 4 Temporal
`WorkerFactory` bean (§11.3). Treat §11 as a pre-emptive checklist, not a post-mortem — and read it
alongside the cited files, which are the real source of truth._
