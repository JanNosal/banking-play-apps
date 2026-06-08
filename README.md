# Banking Playground (BIAN · Spring Boot · Temporal · MongoDB)

> [!WARNING]
> **Experimental playground — do not blindly trust anything here.** This is a personal sandbox I use
> to *explore and investigate* an event-driven data stack (Spring Boot, MongoDB, Temporal,
> Testcontainers). It is **not** professional or expert banking advice, and the "banking" domain is a
> deliberately simplified, made-up approximation — real core-banking, products and regulation work
> very differently. The code and the tech choices are for learning only: they **may be wrong, broken,
> outdated, or incomplete at any time**, sample data is randomly generated (nothing real or
> proprietary), some parts are stubbed or left out, and only the fixes strictly necessary to
> demonstrate an idea have been applied. No guarantees, no support — if you stumble in, treat
> everything as a starting point to verify yourself, not as a reference to copy.
>
> Best-practice development is also intentionally relaxed here: there's one contributor (+ Claude) and
> the repo exists for rapid exploration, so I often commit straight to `main`. That's the wrong call for
> serious development, but it suits this sandbox — the practical consequence is that **I don't guarantee
> `main` always builds or works**, the way one normally would in serious development. Correctness isn't
> the focus here. The project version is deliberately pinned at **`0.0.1`** to signal the same thing:
> this is far from reliable, finished, or stable.

A personal sandbox for poking at an event-driven banking stack (Spring Boot, MongoDB, Temporal,
Testcontainers) on a realistic domain model. The "banking" here follows [**BIAN**](https://bian.org)
(the Banking Industry Architecture Network — banking's equivalent of TM Forum for telecom): the
core records model a bank's **Customer Product and Service Directory** — each customer's holdings
(current/savings accounts, debit cards, overdrafts, loans) — as a `DirectoryEntry` referencing a
`ProductDirectory` entry, a `Product`, its `Service`s and a `ProductAgreement`.

This is a **general playground, not one project**. It'll grow whatever experiment I feel like trying;
the first one built here is below. Everything builds and runs locally in Docker.

> 📖 **Full documentation:** [`docs/`](docs/README.md) — a navigable tree (architecture · testing ·
> build/run) explaining how each block is implemented and how to reuse the pattern. To author tests,
> see [`.github/copilot-instructions.md`](.github/copilot-instructions.md). Refresh the docs after
> changes with the `/refresh-docs` command.

**First experiment — a directory migration.** Move a customer's directory from a *legacy* core into a
*modern* one, orchestrated by **Temporal**:

```
                 discovery workflow                          loader workflow (singleton, endless)
                 ┌──────────────────────┐  enqueueCustomer   ┌───────────────────────────────────┐
  legacy   ◀─────│ scan by directory ref│ ─ signals (FIFO ──▶│ drain queue, bounded concurrency,  │──▶ new
  directory  GET │ (PD-PREMIER-BANKING) │   + dedup set)     │ each customer = 1 activity that    │   directory
  (mock)    page │ page→page→last page  │                    │ fetches the full directory over    │   (target)
  :8085          └──────────────────────┘  discoveryPassDone │ virtual threads and upserts entries│   :8086
                          ▲  re-scan forever (continueAsNew)  └───────────────────────────────────┘
                          └─────────────────────────────────────────  migration-worker :8087  ─────┘
```

## What's here

| Module | What it is | Port |
|--------|-----------|------|
| [`libs/product-model`](libs/product-model) | Pure Java records for the BIAN `DirectoryEntry` aggregate and its parts — `Product`, `Service`, `ProductFeature`, `ProductAgreement`, `Involvedparty` (Jackson only). | — |
| [`apps/mocked-apps`](apps/mocked-apps) | **Legacy** core (mock). CRUD + query under `/customer-product-and-service-directory`; seeds 10k+ entries. | 8085 |
| [`apps/product-inventory-service`](apps/product-inventory-service) | **Modern** core (the target). CRUD under `/customer-product-and-service-directory`; idempotent upsert. | 8086 |
| [`apps/migration-worker`](apps/migration-worker) | Temporal worker hosting the first experiment: discovery + loader workflows. | 8087 |
| [`e2e-tests`](e2e-tests) | Workflow unit test + in-JVM and dockerised end-to-end tests. | — |

Tech: Java 25 · Spring Boot 4.0 (Spring MVC, servlet, **no WebFlux**) · MongoDB · Temporal SDK 1.31 ·
synchronous `RestClient` + **virtual threads** · Testcontainers 2.0.

## The data model

Modelled on the BIAN **Customer Product and Service Directory** service domain. The migrated unit is a
`DirectoryEntry` — one product/service a customer holds — pointing at a **Product Directory** reference
(`productDirectoryReference`, prefix `PD-`), and carrying a `Product` (with `productType` like
`CurrentAccountProduct`, and `ProductFeature[]` for account number, IBAN, ledger account, card, …),
zero or more `Service`s (BIAN `Servicetypevalues`, e.g. `FinancialService`), and a `ProductAgreement`
(BIAN `Productagreementtypevalues`, e.g. `CurrentAccountAgreement`, with the fee/term).

A Premier Banking customer holds a Premier current account whose `productDirectoryReference` is
**`PD-PREMIER-BANKING`** — the marker discovery scans for — plus a debit card (`PD-DEBIT-CARD`),
sometimes an overdraft (`PD-OVERDRAFT`), and one or two savings accounts (`PD-SAVINGS-ACCOUNT`). A
second, unrelated proposition — Mortgage (`PD-MORTGAGE-LOAN` + `PD-HOME-INSURANCE`) — exists so the
migration can be proven *selective*: a customer holding only those is never migrated. Each entry is
tied to a customer via `customerReference` (an `Involvedparty` with role `Customer`); the legacy API is
queryable by `customerReference` (paginated) and by `productDirectoryReference`.

The seeder ([`DataSeeder`](apps/mocked-apps/src/main/java/dev/jannosal/bank/mockedapps/seed/DataSeeder.java))
builds a realistic spread from a fixed RNG seed: most customers hold one set of products, a few are
"whales" with dozens; some hold only the Mortgage proposition (never migrated), some hold both.
Defaults to ~10k+ directory entries across 1,500 customers.

## How the migration experiment works

1. **DiscoveryWorkflow** pages the legacy directory by `PD-PREMIER-BANKING`. For each *new* customer id
   (deduplicated within the pass by an ordered `LinkedHashSet`) it signals `enqueueCustomer` to the
   loader. Pagination tells it when a pass ends. In production it sleeps and `continueAsNew`s — an
   endless re-scan that picks up changes; a positive `maxPasses` makes it finite (tests).
2. **CustomerLoaderWorkflow** is a long-running singleton owning the queue (an ordered, deduplicating
   FIFO). It drains up to `maxConcurrency` customers in parallel; each is a `loadCustomer` activity
   that fetches the customer's **full directory** from the legacy API (page fetches fanned out over
   virtual threads) and upserts every entry into the new directory (also virtual threads). Idempotent
   upserts make activity retries safe. It `continueAsNew`s periodically to keep history small, and
   stops cooperatively on `requestStop`.

## Run it

```bash
make up                              # build images + start mongo, temporal, both inventories, worker
open http://localhost:8234           # Temporal UI — watch discovery + loader
make status                          # loader progress + both inventory counts
make down                            # stop (make reset wipes volumes)
```

Or drive it manually (worker started with `AUTO_START=false`):

```bash
curl -X POST http://localhost:8087/migration/start
curl       http://localhost:8087/migration/status
curl -X POST http://localhost:8087/migration/stop      # cooperative interrupt
```

## Test it

Three layers, fastest first:

```bash
./mvnw verify                          # 1. unit + workflow-logic tests (mocked activities, no Docker)
./mvnw -Pit verify                     # 2. + in-JVM E2E (real Mongo via Testcontainers + real apps)
                                        #    needs Docker 25+/API 1.44 for Testcontainers
./mvnw -Pit -pl e2e-tests verify \      # 3. + dockerised E2E against the real compose stack
    -De2e.dockerised=true               #    (real Temporal + app containers; builds images; slow)

# On older Docker, run the in-JVM E2E against an externally-managed MongoDB instead:
make e2e-local                         # starts a throwaway mongo on :27019
```

1. [`MigrationWorkflowsTest`](apps/migration-worker/src/test/java/dev/jannosal/bank/migration/workflow/MigrationWorkflowsTest.java)
   — Temporal `TestWorkflowEnvironment` + mocked activities; proves dedup, FIFO and stop in ~2s.
2. [`FullMigrationE2EIT`](e2e-tests/src/test/java/dev/jannosal/bank/e2e/FullMigrationE2EIT.java)
   — boots real MongoDB + both apps in-JVM, runs the real workflows, asserts the new inventory is
   **exactly** the migrated subset of the legacy one (full payload equality) **and** that
   non-Premier customers are absent. ~4s.
3. [`DockerisedStackE2EIT`](e2e-tests/src/test/java/dev/jannosal/bank/e2e/DockerisedStackE2EIT.java)
   — the same assertions against the full **dockerised** stack (real Temporal server + app containers),
   driven over HTTP. Gated behind `-De2e.dockerised=true`.

Every wait is bounded (Awaitility + timed `WorkflowStub.getResult` + `@Timeout`); the endless
production loop is stopped cooperatively via `requestStop`, so the tests never hang.

How these tests are built — and a reusable, AI-followable recipe for E2E-testing this kind of
Spring Boot + Temporal + MongoDB + Testcontainers stack (the three-layer pyramid, what to assert,
reliability rules, and the Docker/Testcontainers/Spring-Boot-4 pitfalls that bite on a fresh machine)
— is written up in [`.github/copilot-instructions.md`](.github/copilot-instructions.md).

---

A personal, generic learning sandbox for the event-driven data stack (Spring Boot, MongoDB, Temporal,
Testcontainers) — no real or proprietary data; all sample records are randomly generated.
