[🏠 Repo](../../README.md) › [📖 Docs](../README.md) › [Architecture](README.md) › **Data Seeding**

# Data Seeding (deterministic test data)

> **Read this when** you need realistic-but-fake data that is **exactly reproducible**, so tests can
> assert precise counts and a migration can be proven *selective*.

## In one line

A fixed-RNG-seed generator builds a realistic spread of customer holdings across three profiles
(Premier-only / Mortgage-only / both); because the seed is fixed, the dataset — and every count derived
from it — is identical every run.

## How it's implemented here (`apps/mocked-apps/.../seed`)

- `SeedProperties.java` — `@ConfigurationProperties("seed")` record: `enabled`, `reset`, `customers`,
  `randomSeed` (default 42), `batchSize`. Bound from env (`SEED_CUSTOMERS`, `SEED_RANDOM_SEED`, …).
- `DataSeeder.java` — an `ApplicationRunner` (and callable from tests/`/admin/seed`):
  - `new Random(props.randomSeed())` → fully reproducible;
  - per customer, a `profile` draw decides Premier / Mortgage / both; a long-tail `drawHoldingCount`
    makes most customers small and a few "whales";
  - Premier customers get a **`PD-PREMIER-BANKING` current account** (the discovery marker), a debit
    card, ~50% an overdraft, and 1–2 savings; Mortgage customers get a loan + insurance (never migrated
    unless also Premier);
  - inserts in batches; returns `SeedStats` whose `migratedCustomers()` = the expected-to-migrate count.

```java
public record SeedStats(int customers, int premierOnlyCustomers, int mortgageOnlyCustomers,
                        int bothCustomers, long totalEntries) {
  public int migratedCustomers() { return premierOnlyCustomers + bothCustomers; }
}
```

## Why / key decisions

- **Fixed seed = exact assertions.** Tests assert `isEqualTo`, not "greater than", because the data is
  deterministic (see [Reliability](../02-testing/reliability-and-temporal-testing.md)).
- **Three profiles** exist specifically so **selectivity** is testable: Mortgage-only customers must be
  *absent* from the target.
- **Long-tail distribution** creates whales, which exercises multi-page fetch and the virtual-thread
  fan-out.
- **Same generator, two sizes.** Docker seeds ~10k+ entries across 1,500 customers; the E2E seeds 40
  customers with seed `7`. Only the numbers differ, so the test path mirrors production.

## Reuse in your own project (similar stack)

1. Drive generation from a **single fixed RNG seed** exposed as config.
2. Encode the **expected outcome** in the generator's own stats (here `migratedCustomers()`), so tests
   derive expectations rather than hard-coding them.
3. Include a **negative cohort** (data that must *not* be processed) to make selectivity assertable.
4. Use the **same generator** for the big local dataset and the tiny deterministic test dataset.

## See also

- [Configuration](configuration.md) — how `seed.*` is bound and overridden.
- [In-JVM E2E tests](../02-testing/in-jvm-e2e-tests.md) — how the seed drives exact parity assertions.
