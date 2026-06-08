[🏠 Repo](../../README.md) › [📖 Docs](../README.md) › [Testing](README.md) › **Layer 2 · In-JVM E2E**

# Layer 2 · In-JVM E2E (real apps + real DB)

> **Read this when** you want to prove data is copied **correctly and selectively** through the real
> services, serialization, and database — fast, in one JVM.

## In one line

Boot a **real MongoDB** (Testcontainers), the **real source and target apps** (random ports), and the
**real workflows + activities** on `TestWorkflowEnvironment` (time-skipping OFF); derive the expected
subset from the source, then assert the target equals it exactly and the excluded cohort is absent.

## How it's implemented here

File: `e2e-tests/src/test/java/.../FullMigrationE2EIT.java`.

- **DB:** `new MongoDBContainer("mongo:7.0")` → two databases (`legacy_inventory`, `new_inventory`).
  Fallback: if `-De2e.mongo.uri=...` is set, the container is skipped and an external Mongo is used (for
  machines where Testcontainers can't reach Docker — see [Troubleshooting](../03-build-and-run/troubleshooting.md)).
- **Apps:** `new SpringApplicationBuilder(App.class).run("--server.port=0", "--spring.mongodb.uri=...",
  "--seed.enabled=true", "--seed.customers=40", "--seed.random-seed=7")`.
- **Temporal:** `TestWorkflowEnvironment.newInstance(TestEnvironmentOptions.newBuilder()
  .setUseTimeskipping(false).build())` with the **real** `DiscoveryActivitiesImpl` / `LoaderActivitiesImpl`.

The assertion shape (the correctness matrix):

```java
Set<String> expected = distinctCustomersWithParentSpec();              // derive from source
for (String c : expected) legacyExpected.putAll(fetchLegacyPackage(c));
// run loader (endless) + discovery (1 pass), bounded wait on stats().processedCustomers(), then requestStop()
assertThat(newInventoryTotal()).isEqualTo(legacyExpected.size());      // count parity
for (String c : expected) assertThat(fetchNewPackage(c)).isEqualTo(fetchLegacyPackage(c));  // PAYLOAD parity
for (String c : nonMatching) assertThat(newTotalForCustomer(c)).isZero();                    // selectivity
```

## Why time-skipping is OFF

With **real** activities doing real HTTP, a skipped clock could fire an activity timeout before a real
call returns. `setUseTimeskipping(false)` keeps the clock real. (Layer 1, with mocked activities, may
leave it on for speed.) See [Reliability](reliability-and-temporal-testing.md).

## Pitfall this layer caught

The selectivity loop must build ids the **same way the seeder does** (`"CUST-%06d"`). An earlier
mismatch (`CUST_%06d`) made the loop assert nothing. Read ids back from the source or share the format.

## Reuse in your own project (similar stack)

1. Real DB via Testcontainers; **separate DBs** for source/target.
2. Boot apps in-JVM on random ports; inject config as **command-line args**.
3. Real activities on a **no-time-skipping** test env.
4. **Derive** expectations from the source; assert **count + full-payload** parity + **selectivity**.
5. Add an **external-DB fallback** (`-De2e.<svc>.uri`) for constrained machines.

## See also

- [Persistence & MongoDB](../01-architecture/persistence-and-mongodb.md) — what indexes/upsert this proves.
- [Layer 3 · Dockerised E2E](dockerised-e2e-tests.md) — the same matrix against the real server.
- [`.github/copilot-instructions.md` §4](../../.github/copilot-instructions.md) — generalized recipe.
