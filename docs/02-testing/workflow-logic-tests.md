[🏠 Repo](../../README.md) › [📖 Docs](../README.md) › [Testing](README.md) › **Layer 1 · Workflow-logic Tests**

# Layer 1 · Workflow-logic Tests (mocked activities)

> **Read this when** you want to prove a Temporal workflow's *control flow* in milliseconds, without a
> database, HTTP, or a Temporal server.

## In one line

Run the **real** workflows on Temporal's in-memory `TestWorkflowEnvironment` with **Mockito-mocked**
activities, then assert the activities were invoked the right number of times and the workflow's query
state is correct.

## How it's implemented here

File: `apps/migration-worker/src/test/java/.../workflow/MigrationWorkflowsTest.java`.

```java
env = TestWorkflowEnvironment.newInstance();
Worker worker = env.newWorker(TASK_QUEUE);
worker.registerWorkflowImplementationTypes(DiscoveryWorkflowImpl.class, CustomerLoaderWorkflowImpl.class);
worker.registerActivitiesImplementations(mock(DiscoveryActivities.class), mock(LoaderActivities.class));
env.start();
```

Two tests:
- `migratesEachDistinctCustomerExactlyOnce` — stubs two pages where page 2 repeats an id from page 1;
  asserts `verify(loaderActivities, times(5)).loadCustomer(...)` (5 distinct, not 6) and
  `loader.stats().processedCustomers() == 5`. Proves **dedup**, **pagination-stop** (`hasMore=false`),
  and that the loader drains in **drain-and-exit** mode.
- `requestStopEndsTheLoaderEvenWithoutDiscovery` — starts the loader **not** in drain-and-exit mode
  (models the endless production loop), enqueues two ids, sends `requestStop()`, asserts it terminates.
  Proves the **cooperative stop** path.

Each test carries `@Timeout(value = 30, unit = SECONDS)` as a hard kill-switch.

## What to assert at this layer

Dedup · FIFO/ordering · pagination terminates on the last page · stop-signal ends an endless workflow ·
continue-as-new preserves the pending queue. **Do not** assert data correctness here — that's
[Layer 2](in-jvm-e2e-tests.md).

## Reuse in your own project (similar stack)

1. `TestWorkflowEnvironment` + real workflow impls + **mocked** activities.
2. Drive inputs via the real signals; read state via the real `@QueryMethod`.
3. Assert **call counts** (`Mockito.times(n)`) for dedup/idempotency of the *control flow*.
4. Put a `@Timeout` on every test; fetch results with the bounded `WorkflowStub.getResult(t, unit, ...)`.

## See also

- [Temporal workflows](../01-architecture/temporal-workflows.md) — the logic under test.
- [`.github/copilot-instructions.md` §3](../../.github/copilot-instructions.md) — the generalized recipe.
