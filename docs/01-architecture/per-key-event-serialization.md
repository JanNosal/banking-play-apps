[🏠 Repo](../../README.md) › [📖 Docs](../README.md) › [Architecture](README.md) › **Per-Key Event Serialization**

# Per-Key Event Serialization (workflow id as a lock)

> **Read this when** independent events must be folded into shared per-key state without races, and
> some action fires once that state reaches a target — with a deadline bounding the wait.

> **Note** — a reusable pattern, not a description of code in this repository. Nothing under `apps/`
> implements it yet.

## In one line

Make the **workflow id the lock**: route every event for a key into a single workflow named after that
key, let signals queue, drain them one at a time, and hold the deadline in a `Workflow.await` that wakes
on either a new event or expiry.

## When this applies

The pattern fits when all of these are true:

- Events arrive **independently** — different producers, partitions, or times — but several of them
  describe **members of the same aggregate**.
- Aggregate state must be **folded from many events**, so each event's handling depends on what earlier
  events established.
- Something fires when the aggregate reaches a **target composition** — an external call, a state
  transition, a notification.
- A **deadline** bounds the wait, with a different outcome if it expires.
- Concurrent events for one aggregate **must not race**: no double-firing, no decision made against
  half-written state.

Gaps between events can be arbitrary — seconds or months. That's the case that breaks naive designs and
the one this handles directly.

Worked example used throughout: items belong to an aggregate; an **effect** is applied when the first
item appears; the aggregate is **complete** when every required item is present; if it doesn't complete
within 60 days the effect is reverted. Substitute your own nouns — the mechanics don't change.

## The core idea

Temporal permits at most one *open* run per workflow id per namespace. Name the workflow after the
aggregate key and that constraint becomes a mutex you didn't have to build:

> **Workflow id `agg-<key>` is the lock.** Every mutation of that aggregate's state happens inside a run
> of that id, and there is never more than one.

It holds while the run is parked *and* across completed runs:

- Concurrent `signalWithStart` calls are serialized server-side on the workflow id. If a run is open they
  all become signals into it; if none is open, one starts the run and the rest signal it. Two runs of the
  same id cannot exist.
- A signal landing while a run is completing is **not** lost — the server rejects the
  `CompleteWorkflowExecution` command with `UnhandledCommand` and reschedules the workflow task so the SDK
  processes the signal.
- A signal after the run has fully closed starts a new run, which reloads state from the database.

You get mutual exclusion, a durable deadline, and crash recovery from one construct. No row locks, no
optimistic-version retry loops, no distributed lock, no external scheduler.

## The pattern

```
producer ──► resolve key ──► signalWithStart("agg-" + key, event)
                                      │
                                      ▼
                        ┌──────────────────────────────────┐
                        │  AggregateWorkflow               │  id: agg-<key>
                        │                                  │
                        │  load state from DB              │
                        │  ┌────────────────────────────┐  │
                        │  │ drain inbox (one at a time)│  │
                        │  │ decide + external calls    │  │
                        │  │ persist state              │  │
                        │  │ await(deadline, inbox)     │  │
                        │  └──────────┬─────────────────┘  │
                        │             └── loop ────────────│
                        │  return once settled and quiet   │
                        └──────────────────────────────────┘
```

Two moving parts: whatever already consumes your events, and one workflow type.

### 1. Resolve the key at the edge, then `signalWithStart`

The workflow id must be known before you can signal, so the key has to be resolved by the producer. If
events don't always carry it, look it up there.

```java
String key = event.getAggregateKey() != null
        ? event.getAggregateKey()
        : keyLookup.resolve(event.getMemberId());   // your mapping table

AggregateWorkflow stub = client.newWorkflowStub(AggregateWorkflow.class,
        WorkflowOptions.newBuilder()
                .setTaskQueue(TASK_QUEUE)
                .setWorkflowId("agg-" + key)
                // leave setWorkflowExecutionTimeout unset — it would kill a run mid-deadline
                .build());

BatchRequest req = client.newSignalWithStartRequest();
req.add(stub::run, new StartArgs(key));      // used only if no run is open
req.add(stub::onMemberEvent, event);         // always delivered
client.signalWithStart(req);
```

Always `signalWithStart` — never "check if running, then start or signal". That check is itself a race.

Acknowledge the source message (commit the Kafka offset, ack the queue) **only after this call returns**,
so a failure means redelivery rather than a lost event.

> If the lookup is unreachable from the producer — no DB access, or you refuse to block a consumer poll
> loop on a query — put it in an activity inside a tiny one-shot workflow that resolves and forwards.
> That's a deployment constraint, not a change to the pattern.

### 2. The signal handler only buffers

**This is the mistake that causes the race the pattern is meant to prevent.** A handler that calls an
activity yields, the next handler starts, and two handlers interleave halfway through a decision.
Temporal orders signal *delivery*; it does not stop handlers from overlapping once they block.

```java
private final Queue<MemberEvent> inbox = new ArrayDeque<>();

@SignalMethod
public void onMemberEvent(MemberEvent e) {
    inbox.add(e);        // no activity calls, no awaits, no blocking — ever
}
```

### 3. Load, drain, decide, persist, await

```java
@WorkflowMethod
public Outcome run(StartArgs args) {
    state = activities.loadState(args.key());        // DB is the source of truth between runs

    while (true) {
        drain();                                     // process buffered events one at a time

        if (!state.isTerminal()) {
            if (!state.effectApplied() && state.hasAnyMember()) {
                activities.applyEffect(state.key(), state.definitionVersion());
                state.markEffectApplied(Workflow.currentTimeMillis() + DEADLINE.toMillis());
            } else if (state.isComplete()) {         // ALL required members present
                state.markComplete();                // the only early exit from the wait
            } else if (state.wasEverComplete()) {
                revert();                            // a completed aggregate broke
            } else if (state.effectApplied()
                    && Workflow.currentTimeMillis() >= state.deadlineMillis()) {
                revert();                            // deadline passed, still incomplete
            }
        }

        activities.persistState(state);              // yields — new signals may land here

        if (waiting()) {
            long remaining = state.deadlineMillis() - Workflow.currentTimeMillis();
            Workflow.await(Duration.ofMillis(remaining),
                           () -> !inbox.isEmpty() || shouldContinueAsNew());
            if (inbox.isEmpty() && shouldContinueAsNew()) {
                Workflow.continueAsNew(new StartArgs(state.key()));   // state lives in the DB
            }
            continue;                                // re-evaluate: new events, or expiry
        }

        if (inbox.isEmpty()) {
            return state.outcome();                  // settled and quiet — close the run
        }
    }
}

private boolean waiting() {
    return !state.isTerminal() && state.effectApplied() && !state.isComplete()
            && Workflow.currentTimeMillis() < state.deadlineMillis();
}

private void revert() {
    activities.revertEffect(state.key(), state.definitionVersion());
    state.markReverted();                            // terminal
}
```

Each pass through `drain()`:

1. **Drop stale and duplicate events** — see rule 5 below.
2. Fold the event into state (`state.upsert(e)`).
3. Re-evaluate the target condition, e.g. `definition.required() ⊆ state.activeMembers()`.

**Lifetime**: open while waiting on the deadline, closed once the outcome is settled. A later event on a
settled aggregate starts a fresh run that reloads from the database — which is why persistence is not
optional.

## Rules you cannot break

**1. `await`, never `sleep`.** `Workflow.await(duration, condition)` returns on *either* the condition or
the timeout. `Workflow.sleep(...)` returns only at the timeout — signals still arrive and buffer, but
nothing processes them until it wakes. Using `sleep` for a 60-day deadline is a 60-day outage for that
aggregate.

**2. Waking up is not the same as ending the wait.** An event arriving wakes the run, gets processed, and
the run re-parks if the target still isn't met. 2 of 4 members present falls through every branch and
returns to `await`. Only the target condition or the deadline ends it.

**3. Store an absolute deadline, and persist it.** Compute it once, from `Workflow.currentTimeMillis()`
(deterministic and replay-safe), and recompute `remaining` from it on each iteration. A stored *duration*
would silently restart the clock on every `continueAsNew` or reload. Intermediate events then neither
shorten nor extend the window:

```
day  0   first member  → applyEffect, deadline := day 60, park with 60d remaining
day  3   member 2      → wake, drain, 2/4 → not complete → park with 57d remaining
day 20   member 3      → wake, drain, 3/4 → not complete → park with 40d remaining
day 50   member 4      → wake, drain, 4/4 → COMPLETE → effect stays, run closes
         ── or ──
day 60   nothing more  → await times out → revertEffect, terminal, run closes
```

**4. Drain to empty before returning.** `persistState` yields, so signals can land after your last
`drain()`. The `if (inbox.isEmpty())` guard before `return` is the only thing stopping them from being
dropped. (Java logs a warning for unhandled signals at close — make that a test failure.)

**5. Don't rely on event order; make the fold commutative.** Partition ordering upstream does not survive
independent producers and activity retries. Carry a monotonic version or timestamp per member and ignore
anything not newer:

```java
if (e.version() <= state.versionOf(e.memberId())) return;   // stale or duplicate
```

Without this, a stale "removed" event replayed after a "restored" one tears down a healthy aggregate.
**If your events don't carry such a field, adding one is a prerequisite, not an optimization.**

**6. The workflow is the sole writer of its state row.** Mutual exclusion comes from the workflow id, not
from the database. If anything else writes that row, load→mutate→persist is a lost-update bug and none of
the guarantees hold. `persistState` itself must be an idempotent upsert keyed on the aggregate key.

**7. Idempotency keys on every external side effect.** State flags prevent *logical* re-application;
activity retries still happen after a call succeeded remotely and failed to acknowledge. Pass a stable key
(`aggregateKey + definitionVersion`) and have the remote system deduplicate on it.

**8. Terminal is sticky.** Once the outcome is final, every later run must load that state and no-op. This
is what makes "the decision is final" hold against an event arriving the day after the deadline.

**9. Fire side effects before checking the target condition.** If every member arrives in one batch, the
first drain produces an already-complete aggregate; a completeness check placed first would skip the
initial effect entirely.

**10. Leave `setWorkflowExecutionTimeout` unset.** It defaults to unlimited. Setting it below the deadline
terminates runs mid-wait.

## What this costs

Waiting is free. A parked workflow holds no thread, no worker slot, no connection — `Workflow.await`
returns control to the worker and the wait becomes a durable timer in the Temporal server. Workers can be
redeployed or scaled to zero without affecting it, and a signal wakes the run in milliseconds.

Events for one aggregate are serialized, which is the requirement rather than a cost — microseconds of
work each. Different aggregates are independent workflows that never interact.

The one real hazard is a hung activity inside the drain: an external call retrying forever blocks that
aggregate. Bound it with `setStartToCloseTimeout` and a retry policy that expires, and the backpressure
stays confined to the one key.

Open runs are bounded by aggregates *currently waiting*, not by all aggregates ever, because runs close
once settled.

## When not to use it

- **No deadline and no fold.** If each event is independently processable, you don't need serialization —
  process it and move on.
- **No deadline, but shared state.** A transactional row (`SELECT ... FOR UPDATE` or an optimistic version
  column) is simpler and adequate. The durable timer is what earns Temporal its place here; without it
  you're paying for machinery you don't use.
- **Extreme fan-in on a single key.** All events for one key funnel through one workflow. Thousands per
  second against a single aggregate will queue. Shard the key or batch upstream.

## How to prove it

Use `TestWorkflowEnvironment` with skipped time — see
[Reliability & Temporal testing](../02-testing/reliability-and-temporal-testing.md).

| Test | Asserts |
|------|---------|
| **Waiting doesn't block** | First member parks the run; signal member 2 → processed immediately, virtual time barely advances. Fails if someone rewrites `await` as `sleep`. |
| **Partial arrivals keep waiting** | 4-member aggregate, 3 members across days 3/20/50 → run still open, no revert, deadline still the original instant — neither ended nor extended. |
| **Concurrency** | N simultaneous events for one key → one run at a time, one `applyEffect`, one deadline. |
| **Completion boundary** | Event timed to land while a settled run is persisting-and-closing → processed by that run or a fresh one, never dropped. |
| **All-at-once** | Every member in one batch → `applyEffect` still fires. |
| **Deadline expiry** | Advance past the deadline → `revertEffect` once, terminal, run closed. |
| **Late arrival** | Signal after the terminal outcome → new run loads terminal state, calls nothing. |
| **Break after target met** | Reach the target (run closes), then remove a required member → fresh run reloads, reverts once. |
| **Stale event** | Replay an old event with a lower version → dropped, state unchanged. |
| **continueAsNew mid-wait** | Force it → deadline still measured from the original instant. |
| **Handler discipline** | No `@SignalMethod` calls an activity or awaits. |

## See also

- [Temporal workflows](temporal-workflows.md) — the producer/consumer pipeline implemented in this repo.
- [Concurrency & virtual threads](concurrency-and-virtual-threads.md) — bounded concurrency within a
  single workflow.
