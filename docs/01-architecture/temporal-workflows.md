[🏠 Repo](../../README.md) › [📖 Docs](../README.md) › [Architecture](README.md) › **Temporal Workflows**

# Temporal Workflows (discovery + loader)

> **Read this when** you need a durable producer/consumer pipeline: one workflow discovers work and
> streams it (deduplicated) to a long-running, bounded-concurrency consumer that never loses progress.

## In one line

A **discovery** workflow paginates the source and signals each *new* customer id to a **loader**
workflow — a long-running singleton that drains an ordered, deduplicating FIFO with bounded concurrency,
stops cooperatively, and `continueAsNew`s to keep history small.

## How it's implemented here (`apps/migration-worker/.../workflow`)

### DiscoveryWorkflow — the producer
`DiscoveryWorkflowImpl.discover(DiscoveryParams)`:
- pages `fetchCustomerPage(productDirectoryReference, offset, pageSize)` until `hasMore` is false;
- dedups within a pass with an ordered `LinkedHashSet seen`, signalling `loader.enqueueCustomer(id)` for
  each new id;
- signals `loader.discoveryPassComplete()`, then either stops (bounded `maxPasses`, used by tests) or
  `Workflow.continueAsNew(params.withPassNumber(next))` for an endless re-scan (production).

### CustomerLoaderWorkflow — the consumer (the interesting one)
`CustomerLoaderWorkflowImpl.run(LoaderParams)` is a single `while(true)` loop:

```java
// 1. compaction: bound history without losing the queue
if (continueAsNewAfter > 0 && processedCustomers >= continueAsNewAfter) {
    drainAll(running); Workflow.continueAsNew(params.withInitialPending(new ArrayList<>(pending)));
}
// 2. cooperative stop (the production "interrupt", used by tests)
if (stopRequested) { drainAll(running); break; }
// 3. batch/test mode: discovery done + everything processed → exit
if (drainAndExitWhenComplete && passComplete && pending.isEmpty() && running.isEmpty()) break;
// 4. fill up to maxConcurrency slots
while (running.size() < max && !pending.isEmpty())
    running.add(new Running(pollFirst(), Async.function(activities::loadCustomer, id, pageSize)));
// 5. nothing running → block until work/stop/passComplete (never busy-wait)
if (running.isEmpty()) { Workflow.await(() -> stopRequested || !pending.isEmpty() || (drain && passComplete)); continue; }
// 6/7. wait for at least one completion, then reap completed promises
Workflow.await(() -> stopRequested || anyCompleted(running));
reapCompleted(running);
```

- **Queue** = `LinkedHashSet<String> pending` → ordered (FIFO) **and** deduplicating; re-signalling a
  queued id is a no-op, but an already-loaded customer can be re-enqueued on a later pass (picks up
  changes).
- **Signals/queries** are declared on `CustomerLoaderWorkflow`: `@SignalMethod enqueueCustomer`,
  `discoveryPassComplete`, `requestStop`; `@QueryMethod stats()` returns a `LoaderStats` snapshot.
- **Activity options** (in `CustomerLoaderWorkflowImpl`): `StartToCloseTimeout(5m)`,
  `HeartbeatTimeout(30s)`, `RetryOptions` with `maxAttempts(5)`.

### Activities — `apps/migration-worker/.../activity`
- `DiscoveryActivitiesImpl.fetchCustomerPage(...)` → distinct customer ids on a page.
- `LoaderActivitiesImpl.loadCustomer(...)` → fetches the customer's full directory (paged) and upserts
  each entry; idempotent so retries are safe; fans out over virtual threads and heartbeats
  (see [Concurrency](concurrency-and-virtual-threads.md)).

### Wiring & control plane
- `config/WorkerConfig.java` — builds the `WorkerFactory`, registers both workflows + activities on one
  task queue, sets virtual-thread activity execution. (See the Boot-4 bean note in
  [Troubleshooting](../03-build-and-run/troubleshooting.md).)
- `control/MigrationService.java` — starts the **loader before** discovery (so signals always land),
  making each start idempotent via `WorkflowExecutionAlreadyStarted`.

## Why / key decisions

- **Singleton loader + signals** decouples discovery rate from load rate and gives one place to bound
  concurrency and observe progress.
- **`LinkedHashSet` queue** gives dedup + FIFO in one structure — no separate "seen" set inside the
  consumer.
- **`continueAsNew`** keeps workflow history bounded for an endless process; the pending queue is carried
  forward so nothing is lost.
- **Cooperative `requestStop`** is how the otherwise-endless loop is ended in production *and* in tests —
  no thread interruption, no lost in-flight work (it `drainAll`s first).

## Reuse in your own project (similar stack)

1. Split **discovery (producer)** from **processing (consumer)**; stream via signals.
2. Use one **ordered+dedup collection** as the queue.
3. Bound concurrency with `Async.function` + `Workflow.await(anyCompleted)`; **never** `Thread.sleep` or
   busy-wait inside a workflow.
4. Add a **`@QueryMethod` progress snapshot** — your tests and dashboards will both poll it.
5. Make the long-runner **endless with `continueAsNew`** and **stoppable with a signal**; carry pending
   state across the boundary.
6. Make the unit-of-work activity **idempotent** and give it timeouts + heartbeats + retries.

## See also

- [Layer 1 · Workflow-logic tests](../02-testing/workflow-logic-tests.md) — proving this control flow fast.
- [Reliability & Temporal testing](../02-testing/reliability-and-temporal-testing.md) — time-skipping,
  bounded waits, idempotency.
- [Concurrency & virtual threads](concurrency-and-virtual-threads.md) — the fan-out inside an activity.
