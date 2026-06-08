[🏠 Repo](../../README.md) › [📖 Docs](../README.md) › [Testing](README.md) › **Reliability & Temporal Testing**

# Reliability & Temporal Testing (the cross-cutting rules)

> **Read this when** you ask "how were the tests made *reliable* (no hangs, no flakes) while exercising
> Temporal and MongoDB?" These rules apply to all three layers.

## The rules (and where each lives in the repo)

| Rule | Why | In this repo |
|------|-----|--------------|
| **Every wait is bounded** (Awaitility `atMost` + timed `getResult` + `@Timeout`) | fail fast, never hang | all three test files |
| **No `Thread.sleep` to await work** — poll a real condition | sleeps are slow + racy | tests poll `loader.stats()` / `/stats` |
| **Deterministic seed** | exact `isEqualTo` assertions | `SeedProperties.randomSeed`, `DataSeeder` |
| **Derive expectations from the source** | survives seed changes | `distinctCustomersWithParentSpec()` |
| **Idempotency asserted** | retries/re-scans must not duplicate | loader runs endless; count still exact |
| **Time-skipping OFF for real activities** | skipped clock kills real I/O | `setUseTimeskipping(false)` (Layer 2) |
| **Full-payload equality** | counts hide field corruption | `DirectoryEntry` is a `record` |

## Temporal-specific testing notes

- **Time-skipping:** mocked activities (Layer 1) may keep it **on** (timers/`Workflow.sleep` return
  instantly → fast); real activities (Layer 2) must turn it **off**.
- **Signals & queries as the test API:** drive with `@SignalMethod`s (`enqueueCustomer`, `requestStop`)
  and poll the `@QueryMethod` (`stats()`). Never sleep waiting for a workflow.
- **Ending an endless workflow:** send the cooperative `requestStop` signal, then bound-wait on
  `WorkflowStub.fromTyped(wf).getResult(30, SECONDS, Void.class)`.
- **continue-as-new:** let it fire mid-run (small `continueAsNewAfter`) and assert final totals are still
  exact — proves nothing is lost across the boundary.
- **Activity retries:** because Temporal retries, the unit-of-work activity must be **idempotent**
  (target upserts by id) and declare `StartToCloseTimeout` + `HeartbeatTimeout` + `RetryOptions`.

## Timeout numbers used here

Activity `StartToClose` 5m / `Heartbeat` 30s / retry maxAttempts 5; discovery activity `StartToClose`
2m; HTTP connect 5s / read 30s; Awaitility 60–120s; `getResult` 30s; `@Timeout` 180s (in-JVM) / 600s
(dockerised) / 30s (Layer 1). If a test hangs, **add the missing bound** — don't inflate the others.

## Reuse in your own project (similar stack)

Adopt the table above verbatim. The single most important habit: **never wait without a bound, and
never `Thread.sleep` instead of polling a real signal.**

## See also

- [Temporal workflows](../01-architecture/temporal-workflows.md) — the signals/queries you poll.
- [Concurrency & virtual threads](../01-architecture/concurrency-and-virtual-threads.md) — heartbeats.
- [`.github/copilot-instructions.md` §2, §7, §10](../../.github/copilot-instructions.md).
