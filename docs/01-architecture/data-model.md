[🏠 Repo](../../README.md) › [📖 Docs](../README.md) › [Architecture](README.md) › **Data Model**

# Data Model (BIAN Customer Product & Service Directory)

> **Read this when** you need to model a banking (or any reference-data) domain as a clean, serializable
> contract shared across services — or you want to know why these records look the way they do.

## In one line

The migrated unit is a `DirectoryEntry` — one product/service a customer holds — modelled on the BIAN
*Customer Product and Service Directory* service domain, expressed as **immutable Java `record`s** with
Jackson annotations and nothing else (no Spring, no persistence coupling).

## How it's implemented here

Module `libs/product-model` (package `dev.jannosal.bank.product.model`):

| Record | Represents | Notable |
|--------|-----------|---------|
| `DirectoryEntry` | the aggregate (one holding) | helpers `id()` and `customerId()`; fields `productDirectoryReference`, `customerReference`, `product`, `service`, `productAgreement` |
| `Involvedparty` | a party reference (customer, servicer) | BIAN `partyReference` + role |
| `Product` | the held product (BQ) | `productType` carries BIAN `Bankingproducttypevalues`-style values |
| `ProductFeature` | a product facet | folds the old TMF `characteristic` + `backingResource` |
| `Service` | a supporting service (BQ) | BIAN `Servicetypevalues` |
| `ProductAgreement` | the agreement/pricing | nested `ProductFee`, `Money`, `AgreementValidityPeriod` |
| `ProductDirectory` | **shared id constants** | `PREMIER_BANKING`, `CURRENT_ACCOUNT`, `MORTGAGE_LOAN`, … |

Every record carries `@JsonInclude(NON_NULL)` + `@JsonIgnoreProperties(ignoreUnknown = true)` so the
JSON is compact and forward-compatible.

```java
// libs/product-model/.../DirectoryEntry.java
public record DirectoryEntry(String directoryEntryInstanceReference, String href, ...,
        Involvedparty customerReference, ..., String productDirectoryReference,
        Product product, List<Service> service, ProductAgreement productAgreement) {
  public String id() { return directoryEntryInstanceReference; }
  public String customerId() { return customerReference == null ? null : customerReference.partyReference(); }
}
```

## Why / key decisions

- **`record`, not class.** Structural `equals`/`hashCode` is what lets the E2E test assert *full-payload*
  parity with one `isEqualTo` (see [In-JVM E2E](../02-testing/in-jvm-e2e-tests.md)). Immutability also
  makes the value safe to pass as a Temporal payload.
- **No framework coupling.** The library depends on Jackson only, so it can be the contract on both
  Spring apps and the worker without dragging in Spring. (Enabled by the BOM-not-parent layout — see
  [Local development](../03-build-and-run/local-development.md).)
- **Shared id constants (`ProductDirectory`).** The seeder, the discovery filter, and the tests all
  reference the same constants, so a rename can't desync them. `PREMIER_BANKING` is the discovery filter;
  `MORTGAGE_LOAN`/`HOME_INSURANCE` exist only to prove the migration is *selective*.
- **Why BIAN.** BIAN is banking's industry reference model (the peer of TM Forum for telecom); using it
  makes the toy domain plausible and teaches real vocabulary. Field names are trimmed from the published
  BIAN schemas to what the sandbox needs.

## Reuse in your own project (similar stack)

1. Put the cross-service contract in a **dependency-light module** (records + Jackson only).
2. Use **immutable records** so you get free structural equality for parity assertions.
3. Centralize the **enum-like ids/filters** in one shared constants class referenced by producers,
   consumers, seeders, and tests alike.
4. Add `@JsonInclude(NON_NULL)` + `@JsonIgnoreProperties(ignoreUnknown = true)` for compact,
   forward-compatible payloads.

## See also

- [Persistence & MongoDB](persistence-and-mongodb.md) — how the record is stored and indexed.
- [Services & REST APIs](services-and-apis.md) — how it crosses the wire.
- [Data seeding](data-seeding.md) — how realistic instances are generated deterministically.
