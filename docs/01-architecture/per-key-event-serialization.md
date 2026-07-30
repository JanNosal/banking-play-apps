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

Worked example used throughout: members belong to an aggregate; an **effect** is applied when the first
member appears; the aggregate is **complete** when every required member is present; if it doesn't
complete within 60 days the effect is reverted. Substitute your own nouns — the mechanics don't change.

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

**This closes the window you'd otherwise worry about.** "The run decided the aggregate is complete but
hasn't written it yet, and meanwhile something else reads stale state" cannot happen: while that run is
open, no second run exists, and by the time it closes the write is already out.

## Where the deadline lives

Two defensible options. **Default to keeping it inside the workflow** — that's what this document
describes. The alternative is in [its own section](#alternative-move-the-deadline-into-a-timer-workflow)
with the condition for choosing it.

Keeping the deadline in the workflow means `Workflow.await(remaining, condition)` parks the run until
either a new event or expiry. The deadline is then **intrinsic to the running workflow**: it cannot be
mis-scheduled, forgotten, or lost, because there is no separate artifact to schedule.

The cost is that a run parked for 60 days spans every deploy in those 60 days. Changing the workflow
method's logic breaks replay for runs already in flight (`NonDeterministicException`), so you owe either
`Workflow.getVersion(...)` patching or worker build-id versioning. That's real, but it is **loud** — the
workflow task fails visibly and recovers once you ship a fix. Nothing is lost.

Move the deadline out only if you deploy the worker frequently and won't adopt worker versioning. The
trade you make is a loud, recoverable failure mode for a **silent** one.

## The pattern

```
producer ──► resolve key ──► signalWithStart("agg-" + key, event)
                                      │
                                      ▼
                        ┌──────────────────────────────────┐
                        │  AggregateWorkflow  id: agg-<key>│
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

Two moving parts: whatever already consumes your events, and one workflow type. A third appears only if
resolving the key has side effects — see [the variant below](#variant-when-resolving-the-key-has-side-effects).

### 1. Resolve the key, then `signalWithStart`

The workflow id must be known before you can signal, so the key has to be resolved first. If events don't
always carry it, look it up.

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

This is the right shape when resolution is a **pure read** the producer can perform. When it isn't, use
the [intake workflow variant](#variant-when-resolving-the-key-has-side-effects).

### 2. The signal handler only buffers

**This is the mistake that causes the race the pattern exists to prevent.** A handler that calls an
activity yields, the next handler starts, and two handlers interleave halfway through a decision. Temporal
orders signal *delivery*; it does not stop handlers from overlapping once they block.

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
    if (state.isTerminal()) {
        return state.outcome();                      // decided already; a late event changes nothing
    }

    while (true) {
        drain();                                     // fold every buffered event, one at a time

        if (!state.effectApplied() && state.hasAnyMember()) {
            activities.applyEffect(state.key(), state.definitionVersion());
            state.markEffectApplied(Workflow.currentTimeMillis() + DEADLINE.toMillis());
        } else if (state.isComplete()) {             // ALL required members present
            state.markComplete();                    // the only early exit from the wait
        } else if (state.wasEverComplete()) {
            revert();                                // a completed aggregate broke
        } else if (state.effectApplied() && deadlinePassed()) {
            if (!settle()) revert();                 // nothing else landed — decide
        }

        activities.persistState(state);              // yields — new signals may land here

        if (state.isTerminal()) {
            return state.outcome();
        }

        if (waiting()) {
            Workflow.await(remaining(), () -> !inbox.isEmpty() || shouldContinueAsNew());
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
    return state.effectApplied() && !state.isComplete() && !deadlinePassed();
}

private boolean deadlinePassed() {
    return Workflow.currentTimeMillis() >= state.deadlineMillis();
}

private Duration remaining() {
    return Duration.ofMillis(state.deadlineMillis() - Workflow.currentTimeMillis());
}

/** Wait briefly for work still in flight elsewhere. True if something arrived. */
private boolean settle() {
    if (Workflow.currentTimeMillis() >= state.deadlineMillis() + SETTLE_CAP.toMillis()) {
        return false;                                // hard stop — decide now
    }
    Workflow.await(SETTLE, () -> !inbox.isEmpty());
    return !inbox.isEmpty();
}

private void revert() {
    activities.revertEffect(state.key(), state.definitionVersion());
    state.markReverted();                            // terminal
}
```

And the drain itself:

```java
private void drain() {
    while (!inbox.isEmpty()) {
        MemberEvent e = inbox.poll();
        if (e.version() <= state.versionOf(e.memberId())) continue;  // stale or duplicate
        state.upsert(e);                                             // fold into state
    }
}
```

`drain()` may call activities if you have genuine per-member work — it runs on the workflow thread, one
event at a time. The signal *handler* is what must never do that. But keep decisions out of the loop: fold
everything first, then evaluate once, so three members arriving together produce one evaluation and one
outcome rather than three.

**Lifetime**: open while waiting on the deadline, closed once the outcome is settled. A later event on a
settled aggregate starts a fresh run that reloads from the database — which is why persistence is not
optional even though the run usually holds the state in memory.

### Why `settle()` exists

The deadline expiring does not mean nothing more is coming. An event can be mid-flight elsewhere — still
in an intake workflow, still being repaired against another system — when the clock passes the deadline.
It isn't in the inbox yet, so a naive expiry check would revert while the completing member is one second
away.

`settle()` waits a bounded moment for quiet before deciding, and `SETTLE_CAP` stops it deferring forever
if events keep trickling in. Equivalent alternative: check the deadline against `now - MARGIN` so the
decision simply happens a few minutes late. Use either; don't skip both.

Note this gap is independent of where the deadline lives — it exists in the timer-workflow variant too.

## Rules you cannot break

**1. `await`, never `sleep`.** `Workflow.await(duration, condition)` returns on *either* the condition or
the timeout. `Workflow.sleep(...)` returns only at the timeout — signals still arrive and buffer, but
nothing processes them until it wakes. Using `sleep` for a 60-day deadline is a 60-day outage for that
aggregate.

**2. Waking up is not the same as ending the wait.** An event arriving wakes the run, gets processed, and
the run re-parks if the target still isn't met. 2 of 4 members present falls through every branch and
returns to `await`. Only the target condition or the deadline ends it.

**3. Store an absolute deadline, and persist it.** Compute it once, from `Workflow.currentTimeMillis()`
(deterministic and replay-safe), and recompute `remaining()` from it on each iteration. A stored *duration*
would silently restart the clock on every `continueAsNew` or reload. Intermediate events then neither
shorten nor extend the window:

```
day  0   first member  → applyEffect, deadline := day 60, park with 60d remaining
day  3   member 2      → wake, drain, 2/4 → not complete → park with 57d remaining
day 20   member 3      → wake, drain, 3/4 → not complete → park with 40d remaining
day 50   member 4      → wake, drain, 4/4 → COMPLETE → effect stays, run closes
         ── or ──
day 60   nothing more  → await times out → settle → revertEffect → terminal, run closes
```

**4. Decide after draining, and test the target before the deadline.** The branch order in the decision
block is semantic, not cosmetic. When the last member arrives in the same workflow task as the timeout,
`isComplete()` must be evaluated first so `revert()` never gets a turn.

**5. Close only with an empty inbox.** `persistState` yields, so signals can land after your last
`drain()`. The `if (inbox.isEmpty())` guard before `return` is the only thing stopping them from being
dropped. (Java logs a warning for unhandled signals at close — make that a test failure.)

**6. Don't rely on event order; make the fold commutative.** Partition ordering upstream does not survive
independent producers and activity retries. Carry a monotonic version or timestamp per member and ignore
anything not newer. Without it, a stale "removed" event replayed after a "restored" one tears down a
healthy aggregate. **If your events don't carry such a field, adding one is a prerequisite, not an
optimization.**

**7. The workflow is the sole writer of its state row.** Mutual exclusion comes from the workflow id, not
from the database. If anything else writes that row, load→mutate→persist is a lost-update bug and none of
the guarantees hold. `persistState` must be an idempotent upsert keyed on the aggregate key. Keep a unique
constraint on that key — not as concurrency control, but as an assertion that nothing else writes it.

**8. Idempotency keys on every external side effect.** State flags prevent *logical* re-application;
activity retries still happen after a call succeeded remotely and failed to acknowledge. Pass a stable key
(`aggregateKey + definitionVersion`) and have the remote system deduplicate on it.

**9. Terminal is sticky.** Once the outcome is final, every later run must load that state and no-op —
hence the check immediately after `loadState`. This is what makes "the decision is final" hold against an
event arriving the day after the deadline.

**10. Fire the effect before checking the target.** If every member arrives in one batch, the first drain
produces an already-complete aggregate; a completeness check placed first would skip the initial effect
entirely.

**11. Bound every activity.** Temporal's default retry policy is *unlimited*. An activity against a system
that is down will retry forever, the drain never returns, and that aggregate is wedged with signals piling
up. Set `scheduleToCloseTimeout` (total, including retries) or `maximumAttempts`, and catch the failure per
event so one unprocessable member doesn't block its siblings:

```java
try {
    activities.doPerMemberWork(e);
} catch (ActivityFailure f) {
    state.recordFailed(e);      // park it, alert, keep going
    continue;
}
```

**12. Treat the workflow method as a published contract.** Runs outlive your deploys — that is the whole
point of the deadline living here. Changing the sequence of activity calls, timers, or awaits breaks replay
for runs in flight. Gate changes behind `Workflow.getVersion(...)`, or adopt worker build-id versioning so
old runs stay pinned to the code they started with. **Leave `setWorkflowExecutionTimeout` unset** while
you're at it; setting it below the deadline terminates runs mid-wait.

## Variant: when resolving the key has side effects

Resolution is often not a read. A common case: the event arrives without the key, the mapping store says
it *should* have one, and the upstream record therefore needs **correcting in another system** before
anything else happens. Resolution is then read → remote write → forward.

Don't put that in the consumer. A crash between the repair and the signal leaves the remote data changed
with nothing recording it and nothing retrying the forward, and a slow remote system becomes a stalled
poll loop. Put it in a one-shot **intake workflow**:

```
producer ──► IntakeWorkflow ──► AggregateWorkflow
             id: intake-<eventId>   id: agg-<key>
             resolve → repair → signal
```

```java
@WorkflowMethod
public void intake(MemberEvent event) {
    String key = event.getAggregateKey();

    if (key == null) {
        key = activities.resolveKey(event.getAccountId(), event.getMemberId());
        if (key == null) {
            activities.handleUnaffiliated(event);            // genuinely not part of an aggregate
            return;
        }
        activities.repairUpstreamRecord(event.getMemberId(), key);   // the external write
    }

    activities.signalAggregate(key, event);                  // holds a WorkflowClient
}
```

Started from the producer with a deterministic id:

```java
WorkflowOptions.newBuilder()
        .setTaskQueue(TASK_QUEUE)
        .setWorkflowId("intake-" + eventId)        // topic-partition-offset, or the event's own id
        .setWorkflowIdReusePolicy(WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE)
        .build();
```

Four rules specific to it:

**a. Key it on the event, not the member.** The id is doing *deduplication* duty here, not lock duty. Keyed
on the event, a redelivery is a no-op and different events proceed in parallel. Keyed on the member id you
lose the ability to tell a redelivery from a genuinely new event about the same member — `REJECT_DUPLICATE`
would silently drop real events, and `ALLOW_DUPLICATE` would re-run repairs.

**b. The repair must be idempotent on its own.** Rule (a) only dedups within namespace retention, and
activity retries can re-issue the call regardless. Prefer a compare-and-set on the remote side — "set the
reference if it is currently empty" — over a blind write, or pass an idempotency key.

**c. Repair before signalling.** If downstream work reads the data you're fixing, signalling first lets the
aggregate act on data you already know is wrong. A slow repair is indistinguishable from a genuinely late
event, and the deadline runs from the first member either way.

**d. Signal through an activity.** `Workflow.newExternalWorkflowStub` only signals a workflow that already
exists, and there is no signal-with-start for external workflows from inside workflow code. So the final
hop is an activity holding a `WorkflowClient`. Its retries can deliver the same signal twice — which is
why rule 6 is mandatory, not optional.

> **Where the repair belongs depends on what it writes.** A **per-member** repair ("this record is missing
> its aggregate reference") is safe in intake — concurrent members touch different rows. An
> **aggregate-level** repair is not: two members arriving together would race in the remote system, a
> second race sitting outside your mutex. Move that repair inside the aggregate workflow, where it is
> serialized along with everything else.

None of this touches the aggregate workflow. Only the path into it grows.

## Alternative: move the deadline into a timer workflow

**Choose this only if** you deploy the worker frequently — weekly or more — and won't adopt worker
versioning. The aggregate workflow then closes after every event instead of parking, so no run is ever
long enough to span a deploy and rule 12 stops applying.

The shape: when the effect is applied, an activity starts a one-shot `dl-<key>` workflow with
`setStartDelay` set to the deadline. It wakes up, signals the aggregate, and finishes. The aggregate
workflow drops its `await` and closes whenever the inbox is empty.

```java
public class DeadlineWorkflowImpl implements DeadlineWorkflow {
    @WorkflowMethod
    public void fire(String key) {
        activities.wakeAggregate(key);   // signalWithStart with an empty wake-up signal
    }
}
```

Four rules specific to it:

**a. The wake-up signal is a doorbell, not an instruction.** Its handler is empty; its only job is to make
a run exist. The decision still comes from comparing the clock against the persisted deadline, so a timer
that fires early, twice, or for an aggregate that finished last week is harmless.

**b. Let it always fire.** Don't cancel or terminate the timer when the aggregate completes early — that's
one more thing that can race. A redundant wake-up costs one no-op run.

**c. The scheduling step must fail loudly.** This is the reason the variant is second choice. The deadline
is now an artifact that has to be created successfully; if the scheduling activity's failure is ever
swallowed, the aggregate waits forever and nothing reverts. There is no error and no alert — it looks
exactly like a healthy waiting aggregate. Never catch-and-log that activity.

**d. Add a sweep as a safety net.** Because of (c), run a low-frequency scheduled workflow — daily is
ample for a 60-day deadline — that queries `WHERE deadline < now() AND state = 'WAITING'` and wakes those
aggregates. It costs almost nothing and converts the silent failure mode into a bounded delay.

### Migrating from the `await` shape

The external contract doesn't change: producers still `signalWithStart` on `agg-<key>`, and the state
schema is identical, since both shapes need the persisted absolute deadline. But you can't simply edit the
workflow method — open runs would fail replay, which is the very tax you'd be trying to remove. Introduce
a new type alongside it:

1. **Persist the absolute deadline**, if it isn't already. Compatible with both shapes, so ship it alone.
2. **Add the empty wake-up handler to the existing implementation.** This must land *before* any timer
   exists, so a wake-up reaching an old-shape run is a harmless no-op rather than an unhandled signal.
3. **Add the timer workflow and its scheduling activity.** Nothing calls the activity yet.
4. **Register the new implementation under a new workflow type name** (`AggregateWorkflowV2`), keeping the
   old one registered, and route *new* starts to it. Old runs keep replaying V1 from their own history —
   the type is recorded there, so each run executes the code it started with.
5. **Let old runs drain.** Worst case one full deadline. Don't convert them; an old run deciding via
   `await` plus a new timer waking it would evaluate twice.
6. **Delete V1** once the longest deadline has elapsed.

During the overlap, keep the state schema readable by both (add columns, don't repurpose them), and keep
the scheduling activity idempotent — you can legitimately attempt to schedule a timer for an aggregate
that already has one.

## What this costs

**Head-of-line latency per key.** Events for one aggregate are processed one at a time, so a slow activity
in the drain delays that aggregate's next event. Other aggregates are unaffected — different workflow ids,
nothing shared. The producer never waits: `signalWithStart` returns as soon as the signal is durable.

This is inherent to per-key serialization, not to Temporal — a DB row lock or a single-partition consumer
blocks the same way. The only real mitigation is to shrink the serialized section: per-member work that
doesn't read aggregate state can move to the intake workflow, where members run in parallel.

**Versioning discipline.** Rule 12. The price of the deadline being intrinsic.

**Hot keys.** All events for one key funnel through one workflow. Fine for tens of events; a problem at
thousands per second against a single aggregate.

**Dedup has a horizon.** Deterministic ids plus `REJECT_DUPLICATE` only dedup within namespace retention.
Idempotent side effects cover the rest.

Waiting itself is free: a parked workflow holds no thread, no worker slot, no connection. The wait is a
durable timer on the server, workers can be redeployed or scaled to zero, and a signal wakes the run in
milliseconds. Open runs are bounded by aggregates *currently waiting*, not by all aggregates ever.

## When not to use it

- **No deadline and no fold.** If each event is independently processable, you don't need serialization.
- **No deadline, but shared state.** A transactional row (`SELECT ... FOR UPDATE`, or a version column with
  a conditional update) is simpler and adequate. Note that "insert fails if the row already exists" is *not*
  sufficient — it guards inserts, while the race lives in concurrent updates, where both writers read, both
  decide, and the second silently overwrites the first.
- **Extreme fan-in on a single key.** Shard the key or batch upstream.

## How to prove it

Use `TestWorkflowEnvironment` with skipped time — see
[Reliability & Temporal testing](../02-testing/reliability-and-temporal-testing.md).

| Test | Asserts |
|------|---------|
| **Waiting doesn't block** | First member parks the run; signal member 2 → processed immediately, virtual time barely advances. Fails if someone rewrites `await` as `sleep`. |
| **Partial arrivals keep waiting** | 4-member aggregate, 3 members across days 3/20/50 → run still open, no revert, deadline still the original instant — neither ended nor extended. |
| **Concurrency** | N simultaneous events for one key → one run at a time, one `applyEffect`, one deadline. |
| **Same-task tie** | Last member lands in the same workflow task as the timeout → completes, never reverts. Rule 4's regression test. |
| **Settle window** | A member lands within `SETTLE` after expiry → completes. Beyond `SETTLE_CAP` → reverts. |
| **Close boundary** | Event timed to land while a settled run is persisting-and-closing → processed by that run or a fresh one, never dropped. |
| **All-at-once** | Every member in one batch → `applyEffect` still fires (rule 10). |
| **Deadline expiry** | Advance past the deadline → `revertEffect` once, terminal, run closed. |
| **Late arrival** | A member after the terminal outcome → new run loads terminal state, calls nothing. |
| **Break after target met** | Reach the target (run closes), then remove a required member → fresh run reloads, reverts once. |
| **Stale event** | Replay an old event with a lower version → dropped, state unchanged. |
| **Reload fidelity** | A run started by the 5th event reconstructs what the 4th persisted, including per-member versions. |
| **continueAsNew mid-wait** | Force it → deadline still measured from the original instant. |
| **Wedged activity** | Make a per-member activity fail permanently → the member is parked, siblings still process (rule 11). |
| **Handler discipline** | No `@SignalMethod` calls an activity or awaits. |
| **Replay safety** | Keep histories from real runs and replay them against the current code in CI — the standing guard for rule 12. |

## See also

- [Temporal workflows](temporal-workflows.md) — the producer/consumer pipeline implemented in this repo.
- [Concurrency & virtual threads](concurrency-and-virtual-threads.md) — bounded concurrency within a
  single workflow.
