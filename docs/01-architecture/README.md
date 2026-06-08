[🏠 Repo](../../README.md) › [📖 Docs](../README.md) › **Architecture**

# Architecture

> **Read this when** you want the big picture of what the system does and which moving parts exist
> before diving into a specific page.

## In one line

A Temporal **worker** drives two cooperating workflows that copy a customer's directory records from a
**legacy core** (mock, port 8085) to a **modern core** (port 8086), selectively and idempotently, over
plain HTTP, with MongoDB on each side.

## The topology

```
  discovery workflow                        loader workflow (singleton, endless)
  ┌──────────────────────┐ enqueueCustomer  ┌───────────────────────────────────┐
  │ scan legacy by        │ ─ signals (FIFO ─▶ drain queue, bounded concurrency, │
  │ productDirectoryRef   │   + dedup set)   │ 1 activity/customer fetches the    │──▶ modern
  │ page→page→last page   │                  │ full directory (virtual threads)   │    core :8086
  └──────────────────────┘ discoveryPassDone │ and upserts every entry            │
        ▲ re-scan forever (continueAsNew)    └───────────────────────────────────┘
  legacy core :8085                            migration-worker :8087
```

The same immutable record (`DirectoryEntry`) is the HTTP contract on **both** cores and the Temporal
payload, so nothing has to translate between layers.

## The modules

| Module | Role | Deep dive |
|--------|------|-----------|
| `libs/product-model` | Shared BIAN records (Jackson only, no Spring). | [Data model](data-model.md) |
| `apps/mocked-apps` | Legacy/source core + deterministic seeder. | [Services](services-and-apis.md) · [Seeding](data-seeding.md) |
| `apps/product-inventory-service` | Modern/target core (idempotent upsert). | [Services](services-and-apis.md) · [Persistence](persistence-and-mongodb.md) |
| `apps/migration-worker` | Temporal worker: discovery + loader workflows, control plane. | [Workflows](temporal-workflows.md) |
| `e2e-tests` | The three-layer test suite. | [Testing](../02-testing/README.md) |

## Pages in this section

1. [Data model](data-model.md)
2. [Services & REST APIs](services-and-apis.md)
3. [Temporal workflows](temporal-workflows.md)
4. [Persistence & MongoDB](persistence-and-mongodb.md)
5. [Concurrency & virtual threads](concurrency-and-virtual-threads.md)
6. [Data seeding](data-seeding.md)
7. [Configuration](configuration.md)
8. [Observability & control plane](observability.md)

## See also

- [Testing](../02-testing/README.md) — how all of the above is proven correct.
- [Build & Run](../03-build-and-run/README.md) — how it's compiled, imaged, and launched.
