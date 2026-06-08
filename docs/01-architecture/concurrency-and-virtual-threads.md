[🏠 Repo](../../README.md) › [📖 Docs](../README.md) › [Architecture](README.md) › **Concurrency & Virtual Threads**

# Concurrency & Virtual Threads

> **Read this when** you want high throughput from blocking I/O on Java 21+/25 without reactive code —
> and you need to know where virtual threads are safe around Temporal.

## In one line

Throughput comes from **virtual threads**, not WebFlux: the Spring apps run on virtual threads, and the
loader activity fans page-fetches and upserts out over a virtual-thread executor — while the **workflow
code stays single-threaded and deterministic** (Temporal's requirement).

## How it's implemented here

- **Apps:** `spring.threads.virtual.enabled: true` in each `application.yml` → servlet requests served on
  virtual threads.
- **Worker activity executor:** `config/WorkerConfig.java` sets
  `WorkerOptions.setUsingVirtualThreadsOnActivityWorker(true)` and a generous
  `setMaxConcurrentActivityExecutionSize(...)`, so each concurrent customer load is a virtual thread.
- **Fan-out inside an activity:** `activity/LoaderActivitiesImpl.loadCustomer(...)` uses
  `Executors.newVirtualThreadPerTaskExecutor()` twice — once to fetch the remaining pages in parallel
  after learning the total from page 0, once to upsert all entries in parallel — then heartbeats:

```java
try (ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor()) {
  for (int p = 1; p < pages; p++) { int off = p*pageSize;
    futures.add(exec.submit(() -> legacy.queryByCustomer(customerId, off, pageSize).entries())); }
  for (var f : futures) all.addAll(f.get());
} catch (ExecutionException e) { throw Activity.wrap(e.getCause()); }
Activity.getExecutionContext().heartbeat(all.size());     // don't look "stuck" to the server
```

## Why / key decisions

- **Activities, not workflows, do the parallel I/O.** Workflow code must be deterministic and
  single-threaded; spawning threads there would break replay. All fan-out lives in **activities**, which
  are ordinary code.
- **Virtual threads suit blocking `RestClient` calls** — cheap to create per page/entry, so a "whale"
  customer with many records still completes quickly.
- **Bounded at the workflow level.** Concurrency *across* customers is capped by the loader's
  `maxConcurrency` slots (see [Temporal workflows](temporal-workflows.md)); concurrency *within* a
  customer is the virtual-thread fan-out. Two independent dials.
- **`Activity.wrap(...)`** converts a fan-out failure into a Temporal-retryable exception.

## Reuse in your own project (similar stack)

1. Turn on `spring.threads.virtual.enabled` and skip reactive frameworks for blocking I/O.
2. Do parallel I/O **inside activities** with `newVirtualThreadPerTaskExecutor()`; keep workflow code
   deterministic.
3. Heartbeat long activities so the server doesn't time them out.
4. Keep **two concurrency dials**: across work items (workflow slots) and within an item (thread fan-out).

## See also

- [Temporal workflows](temporal-workflows.md) — the cross-customer concurrency cap.
- [Services & APIs](services-and-apis.md) — the blocking `RestClient` being parallelized.
