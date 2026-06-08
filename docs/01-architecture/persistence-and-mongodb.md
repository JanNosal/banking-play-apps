[🏠 Repo](../../README.md) › [📖 Docs](../README.md) › [Architecture](README.md) › **Persistence & MongoDB**

# Persistence & MongoDB

> **Read this when** you need to store a nested aggregate in MongoDB, query it by a couple of hot fields
> via indexes, and support idempotent upserts.

## In one line

Each `DirectoryEntry` is stored as a nested sub-document, with `customerId` and
`productDirectoryReference` **denormalized to indexed top-level fields** so the two hot queries
(by-customer, by-filter) hit indexes; writes are upserts by `@Id`.

## How it's implemented here

Both cores use the same shape (separate databases, see [Configuration](configuration.md)):

- `persistence/DirectoryEntryDocument.java` — `@Document("directoryEntry")`; `@Id String id`;
  `@Indexed String customerId`; `@Indexed String productDirectoryReference`; the full
  `DirectoryEntry entry` nested. The constructor denormalizes the indexed fields from the record.
- `persistence/DirectoryEntryRepository.java` — Spring Data **derived queries**:
  `findByCustomerId`, `findByProductDirectoryReference`, `findByCustomerIdAndProductDirectoryReference`,
  `countByCustomerId`.
- `service/DirectoryService.java` — `query(...)` picks the query by which filters are present;
  `upsert/save(...)` = `repository.save(new DirectoryEntryDocument(entry))` (replace by id);
  `update(...)` forces the path id onto the stored payload so document `@Id` and payload stay consistent.

```java
// DirectoryEntryDocument constructor — denormalize for indexable queries
public DirectoryEntryDocument(DirectoryEntry entry) {
  this.id = entry.id();
  this.customerId = entry.customerId();
  this.productDirectoryReference = entry.productDirectoryReference();
  this.entry = entry;                 // full nested payload
}
```

## Why / key decisions

- **Nested payload + denormalized index keys.** Keeps the document a faithful copy of the contract while
  still serving the two hot queries by index instead of collection scans.
- **Upsert by id (`save`).** Idempotency is a *persistence* property here, which is what makes Temporal
  activity retries safe end-to-end.
- **Spring Boot 4 connection property.** Connection is `spring.mongodb.uri` (the old
  `spring.data.mongodb.uri` was removed); `auto-index-creation` stays under `spring.data.mongodb`. Both
  apps' `application.yml` reflect this — getting it wrong fails the connection silently.
- **Separate databases** (`legacy_inventory`, `new_inventory`) so source and target can't be confused.

## Reuse in your own project (similar stack)

1. Store the **whole aggregate nested**, but **denormalize + `@Indexed`** the 1–3 fields you actually
   query on.
2. Express hot queries as **derived repository methods** (or explicit indexes) — and exercise them in a
   real-DB test ([Layer 2](../02-testing/in-jvm-e2e-tests.md)).
3. Make writes **upsert-by-id** if any redelivery is possible.
4. On Spring Boot 4, use `spring.mongodb.uri`; keep `auto-index-creation` under `spring.data.mongodb`.

## See also

- [Data model](data-model.md) — the record being stored.
- [In-JVM E2E tests](../02-testing/in-jvm-e2e-tests.md) — where indexes and upsert idempotency are proven.
