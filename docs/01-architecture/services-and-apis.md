[🏠 Repo](../../README.md) › [📖 Docs](../README.md) › [Architecture](README.md) › **Services & REST APIs**

# Services & REST APIs

> **Read this when** you need a paginated, queryable REST contract that another service (or a Temporal
> activity) will page through reliably — including how to signal "last page."

## In one line

Two near-identical Spring MVC services expose the directory under
`/customer-product-and-service-directory`; the **source** supports filtered, paginated reads and the
**target** supports idempotent upsert; total count travels in the `X-Total-Count` header so clients know
when to stop paging.

## How it's implemented here

### Source (legacy) — `apps/mocked-apps`
- `web/LegacyCustomerProductDirectoryController.java` — `GET` list with query params
  `productDirectoryReference` and `customerReference`, plus `offset`/`limit`; returns `X-Total-Count`.
  Also `GET /{id}`, `POST`, `PATCH /{id}`, `DELETE /{id}`.
- `web/AdminController.java` — `/admin/stats` and `/admin/seed` (ops + tests).
- `service/DirectoryService.java` — picks the right repository query from which filters are present.

```java
// LegacyCustomerProductDirectoryController.listEntries(...)
return ResponseEntity.ok()
    .header("X-Total-Count", String.valueOf(page.getTotalElements()))   // ← last-page signal
    .header("X-Result-Count", String.valueOf(entries.size()))
    .body(entries);
```

### Target (modern) — `apps/product-inventory-service`
- `web/CustomerProductDirectoryController.java` — `POST` is an **idempotent upsert by id**
  (`service.upsert(...)`), `GET /{id}`, `GET` list by `customerReference`, `/stats`.

### The client side — `apps/migration-worker/.../client`
- `LegacyInventoryClient.java` — `queryByProductDirectoryReference(...)` and `queryByCustomer(...)`,
  each returning a `Page` record whose `hasMore(offset, limit)` reads `X-Total-Count`.
- `NewInventoryClient.java` — `upsert(entry)` → `POST`.
- `HttpClients.java` — builds `RestClient`s with explicit **connect (5s) + read (30s)** timeouts.

```java
// LegacyInventoryClient.Page
public record Page(List<DirectoryEntry> entries, long total) {
  public boolean hasMore(int offset, int limit) { return (long) offset + limit < total; }
}
```

## Why / key decisions

- **Servlet MVC + synchronous `RestClient`, no WebFlux.** Simpler mental model; throughput comes from
  virtual threads instead (see [Concurrency](concurrency-and-virtual-threads.md)).
- **`X-Total-Count` over cursor pagination.** Lets both the worker and the tests page with a trivial
  `offset + limit >= total` stop rule, reused identically in production and test code.
- **Idempotent upsert on the target.** The whole migration's retry-safety depends on it (Temporal may
  retry an activity); see [Persistence](persistence-and-mongodb.md) and
  [Reliability](../02-testing/reliability-and-temporal-testing.md).
- **Always set both HTTP timeouts.** An unbounded read would stall the worker and hang tests; a timeout
  surfaces as a retryable exception.

## Reuse in your own project (similar stack)

1. Expose **total count in a header** (or envelope) so consumers detect the last page without guessing.
2. Make the **write endpoint idempotent by id** if anything (a queue, a workflow) may redeliver.
3. Wrap your HTTP client with **explicit connect + read timeouts**; never rely on defaults.
4. Share one tiny `Page`/`hasMore` helper between the production client and the tests so paging logic
   can't drift.

## See also

- [Temporal workflows](temporal-workflows.md) — who calls these endpoints and when.
- [Observability & control plane](observability.md) — the `/migration/*` and `/actuator/*` endpoints.
