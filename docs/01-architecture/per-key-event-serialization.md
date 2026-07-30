[🏠 Repo](../../README.md) › [📖 Docs](../README.md) › [Architecture](README.md) › **Per-Key Event Serialization**

# Per-Key Event Serialization (workflow id as a lock)

> **Read this when** independent events must be folded into shared per-key state without races, and
> some action fires once that state reaches a target — with a deadline bounding the wait.

> **Note** — a reusable pattern, not a description of code in this repository. Nothing under `apps/`
> implements it yet.

## In one line

Make the **workflow id the lock**: route every event for a key into a single workflow named after that
key, let signals queue, and drain them one at a time — and keep the deadline in a **separate one-shot
timer workflow**, so no run stays open long enough to span a deploy.

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

It holds while a run is executing *and* across completed runs:

- Concurrent `signalWithStart` calls are serialized server-side on the workflow id. If a run is open they
  all become signals into it; if none is open, one starts the run and the rest signal it. Two runs of the
  same id cannot exist.
- A signal landing while a run is completing is **not** lost — the server rejects the
  `CompleteWorkflowExecution` command with `UnhandledCommand` and reschedules the workflow task so the SDK
  processes the signal.
- A signal after the run has fully closed starts a new run, which reloads state from the database.

That last point is what makes the design work: **runs are short-lived**. Each run loads state, processes
what it was sent, persists, and closes. The database holds state between runs.

## Why the deadline lives in its own workflow

The obvious implementation is `Workflow.await(remaining, condition)` inside the aggregate workflow — the
run parks until either a new event or the deadline. It's correct and it's fewer moving parts.

The problem is not performance. A parked workflow costs nothing: no thread, no worker slot, the wait is a
durable timer on the server. The problem is **time**. A run parked for 60 days will span every deploy you
make in those 60 days, and any change to the workflow method's logic breaks replay for the runs already in
flight (`NonDeterministicException`). You then owe `Workflow.getVersion(...)` patching or worker
versioning forever.

Moving the wait into a **separate one-shot workflow** removes that entirely:

- The aggregate workflow lives milliseconds. Deploy whatever you like, whenever you like.
- The timer workflow is three lines and never changes, so its own versioning risk is nil.

Cost: one extra workflow type, and one open (but idle) run per aggregate currently waiting.

## The pattern

```
producer ──► resolve key ──► signalWithStart("agg-" + key, event)
                                      │
                                      ▼
                     ┌────────────────────────────────────┐
                     │  AggregateWorkflow  id: agg-<key>  │
                     │  load → drain → decide → persist   │
                     │  close once the inbox is empty     │
                     └──────┬──────────────────────▲──────┘
                            │ schedule (once)      │ wake-up signal
                            ▼                      │
                     ┌──────────────────────────────┴─────┐
                     │  DeadlineWorkflow  id: dl-<key>    │
                     │  setStartDelay(until deadline)     │
                     │  → signal the aggregate → done     │
                     └────────────────────────────────────┘
```

Three moving parts: whatever already consumes your events, the aggregate workflow, and a trivial timer.
(A fourth appears only if resolving the key has side effects — see
[the variant below](#variant-when-resolving-the-key-has-side-effects).)

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

### 2. Signal handlers only buffer

**This is the mistake that causes the race the pattern exists to prevent.** A handler that calls an
activity yields, the next handler starts, and two handlers interleave halfway through a decision. Temporal
orders signal *delivery*; it does not stop handlers from overlapping once they block.

```java
private final Queue<MemberEvent> inbox = new ArrayDeque<>();

@SignalMethod
public void onMemberEvent(MemberEvent e) {
    inbox.add(e);        // no activity calls, no awaits, no blocking — ever
}

@SignalMethod
public void onDeadlineWakeup() {
    // intentionally empty — its only job is to make a run exist
}
```

That empty handler is deliberate and load-bearing. See rule 1.

### 3. Load, drain, decide, persist, close

```java
@WorkflowMethod
public Outcome run(StartArgs args) {
    state = activities.loadState(args.key());        // DB is the source of truth between runs

    do {
        drain();                                     // fold every buffered event, one at a time

        if (!state.isTerminal()) {
            if (!state.effectApplied() && state.hasAnyMember()) {
                activities.applyEffect(state.key(), state.definitionVersion());
                long deadline = Workflow.currentTimeMillis() + DEADLINE.toMillis();
                state.markEffectApplied(deadline);
                activities.scheduleDeadlineWakeup(state.key(), deadline);
            } else if (state.isComplete()) {         // ALL required members present
                state.markComplete();
            } else if (state.wasEverComplete()) {
                revert();                            // a completed aggregate broke
            } else if (state.effectApplied() && deadlinePassed()) {
                if (settle()) continue;              // in-flight work landed → re-evaluate
                revert();
            }
        }

        activities.persistState(state);              // yields — new signals may land here

    } while (!inbox.isEmpty());                      // never close with buffered signals

    return state.outcome();
}

private boolean deadlinePassed() {
    return Workflow.currentTimeMillis() >= state.deadlineMillis();
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

Each pass through `drain()`:

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

### 4. The timer workflow

```java
public class DeadlineWorkflowImpl implements DeadlineWorkflow {
    @WorkflowMethod
    public void fire(String key) {
        activities.wakeAggregate(key);   // signalWithStart, empty wake-up signal
    }
}
```

Scheduled once, from the activity invoked when the effect is applied:

```java
WorkflowOptions opts = WorkflowOptions.newBuilder()
        .setTaskQueue(TASK_QUEUE)
        .setWorkflowId("dl-" + key)                       // one per aggregate
        .setWorkflowIdReusePolicy(WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE)
        .setStartDelay(Duration.ofMillis(deadline - System.currentTimeMillis() + MARGIN))
        .build();
try {
    WorkflowClient.start(client.newWorkflowStub(DeadlineWorkflow.class, opts)::fire, key);
} catch (WorkflowExecutionAlreadyStarted e) {
    // already scheduled — nothing to do
}
```

`MARGIN` (minutes) buys the same protection as `settle()` from the other side: fire slightly late so
events that were in flight at the deadline have already landed. Use either or both.

If your SDK/server predates `setStartDelay`, the timer workflow can simply
`Workflow.sleep(untilDeadline)` instead. `sleep` is correct **here** — the "never sleep" rule applies only
to a workflow that must process signals while waiting, and this one receives none.

## Rules you cannot break

**1. The wake-up signal is a doorbell, not an instruction.** Never "signal arrived → revert". The signal's
only job is to make a run exist; the decision comes from comparing the clock against the *persisted*
deadline. That way a timer that fires early, twice, or for an aggregate that finished last week is
harmless — the workflow looks at its own state and shrugs.

**2. Let the timer always fire.** Don't cancel or terminate it when the aggregate completes early. A
cancellation is one more thing that can race; a redundant wake-up costs one no-op run. Correctness must
never depend on killing the timer in time.

**3. Store an absolute deadline, and persist it.** Compute it once and store the instant. A stored
*duration* would silently restart the clock on every reload. Because it's absolute, intermediate events
neither shorten nor extend the window:

```
day  0   first member  → applyEffect, deadline := day 60, schedule dl-<key>
day  3   member 2      → run opens, folds, 2/4 → not complete → closes
day 20   member 3      → run opens, folds, 3/4 → not complete → closes
day 50   member 4      → run opens, folds, 4/4 → COMPLETE → effect stays, closes
day 60   timer fires   → run opens, sees complete → no-op, closes
         ── or ──
day 60   timer fires   → run opens, still 3/4, settles, reverts → terminal
```

**4. Decide after draining, and test the target before the deadline.** The branch order in the decision
block is semantic, not cosmetic. When the last member and the deadline wake-up arrive in the *same*
drain, `isComplete()` must be evaluated first so `revert()` never gets a turn. A member arriving in the
same millisecond as the timer counts.

**5. Close only with an empty inbox.** `persistState` yields, so signals can land after your last
`drain()`. The `while (!inbox.isEmpty())` condition is the only thing stopping them from being dropped.
(Java logs a warning for unhandled signals at close — make that a test failure.)

**6. Don't rely on event order; make the fold commutative.** Partition ordering upstream does not survive
independent producers and activity retries. Carry a monotonic version or timestamp per member and ignore
anything not newer. Without it, a stale "removed" event replayed after a "restored" one tears down a
healthy aggregate. **If your events don't carry such a field, adding one is a prerequisite, not an
optimization.**

**7. The aggregate workflow is the sole writer of its state row.** Mutual exclusion comes from the
workflow id, not from the database. If anything else writes that row, load→mutate→persist is a
lost-update bug and none of the guarantees hold. `persistState` itself must be an idempotent upsert keyed
on the aggregate key. Keep a unique constraint on that key — not as concurrency control, but as an
assertion that nothing else writes it.

**8. Idempotency keys on every external side effect.** State flags prevent *logical* re-application;
activity retries still happen after a call succeeded remotely and failed to acknowledge. Pass a stable key
(`aggregateKey + definitionVersion`) and have the remote system deduplicate on it.

**9. Terminal is sticky.** Once the outcome is final, every later run must load that state and no-op. This
is what makes "the decision is final" hold against an event arriving the day after the deadline.

**10. Fire the effect before checking the target.** If every member arrives in one batch, the first drain
produces an already-complete aggregate; a completeness check placed first would skip the initial effect
entirely.

**11. Bound every activity.** Temporal's default retry policy is *unlimited*. An activity against a system
that is down will retry forever, the drain never returns, and that aggregate is wedged with signals piling
up. Set `scheduleToCloseTimeout` (total, including retries) or `maximumAttempts`, and catch the failure
per event so one unprocessable member doesn't block its siblings:

```java
try {
    activities.doPerMemberWork(e);
} catch (ActivityFailure f) {
    state.recordFailed(e);      // park it, alert, keep going
    continue;
}
```

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

## What this costs

**Head-of-line latency per key.** Events for one aggregate are processed one at a time, so a slow activity
in the drain delays that aggregate's next event. Other aggregates are unaffected — different workflow ids,
nothing shared. The producer never waits: `signalWithStart` returns as soon as the signal is durable.

This is inherent to per-key serialization, not to Temporal — a DB row lock or a single-partition consumer
blocks the same way. The only real mitigation is to shrink the serialized section: per-member work that
doesn't read aggregate state can move to the intake workflow, where members run in parallel.

**Hot keys.** All events for one key funnel through one workflow. Fine for tens of events; a problem at
thousands per second against a single aggregate.

**Dedup has a horizon.** Deterministic ids plus `REJECT_DUPLICATE` only dedup within namespace retention.
Idempotent side effects cover the rest.

Open runs are bounded by aggregates *currently waiting* — the idle timer workflows — not by all aggregates
ever.

## When not to use it

- **No deadline and no fold.** If each event is independently processable, you don't need serialization.
- **No deadline, but shared state.** A transactional row (`SELECT ... FOR UPDATE`, or a version column
  with a conditional update) is simpler and adequate. Note that "insert fails if the row exists" is *not*
  sufficient — it guards inserts, while the race lives in concurrent updates.
- **Extreme fan-in on a single key.** Shard the key or batch upstream.

## Refactor: migrating from an in-workflow `await`

If you already run the simpler shape — the aggregate workflow parking in `Workflow.await(remaining, …)` —
here is how to move to the timer workflow without breaking runs in flight. The external contract does not
change: producers still `signalWithStart` on `agg-<key>`, and the state schema is the same, because both
shapes need the persisted absolute deadline.

The catch is that the migration itself costs exactly the determinism tax you're trying to remove: editing
the workflow method while 60-day runs are open breaks their replay. So don't edit it — introduce a new
type alongside it.

**Step 1 — persist the absolute deadline, if it isn't already.** Compatible with both shapes, so ship it
on its own.

**Step 2 — add the wake-up signal handler to the *existing* implementation.** Empty body. This must land
*before* any timer exists, so that a wake-up arriving at an old-shape run is a harmless no-op rather than
an unhandled signal.

**Step 3 — add the timer workflow and its scheduling activity.** Nothing calls the activity yet.

**Step 4 — register the new aggregate implementation under a new workflow type name**
(`AggregateWorkflowV2`), keeping the old one registered. Route *new* starts to V2. Old runs keep replaying
V1 from their own history — the workflow type is recorded there, so each run executes the code it started
with.

**Step 5 — let the old runs drain.** They finish on their own `await`, worst case one full deadline
(60 days). Don't try to convert them: an old run deciding via `await` plus a new timer waking it would
evaluate twice. Harmless if rules 1 and 9 hold, but pointless.

**Step 6 — after the longest deadline has elapsed, delete V1** and its registration.

During the overlap:

- Keep the state schema readable by both. Add columns, don't repurpose them.
- The scheduling activity must stay idempotent (`REJECT_DUPLICATE`) — during the transition you can
  legitimately attempt to schedule a timer for an aggregate that already has one.
- Both types write the same state rows, and rule 7 still holds per aggregate: one workflow id, one writer.
  V1 and V2 never share a key, because a key's run either predates the cutover or postdates it.

If you haven't shipped the `await` version yet, skip all of this and build the timer shape from the start.
Migrating later is strictly more work than starting there.

## How to prove it

Use `TestWorkflowEnvironment` with skipped time — see
[Reliability & Temporal testing](../02-testing/reliability-and-temporal-testing.md).

| Test | Asserts |
|------|---------|
| **Concurrency** | N simultaneous events for one key → one run at a time, one `applyEffect`, one timer scheduled. |
| **Timer is a doorbell** | Deliver the wake-up when the aggregate is already complete → no-op, no `revertEffect`. |
| **Same-drain tie** | Last member and the wake-up land in one drain → completes, never reverts. Rule 4's regression test. |
| **Settle window** | Wake-up arrives, then a member lands within `SETTLE` → completes. Beyond `SETTLE_CAP` → reverts. |
| **Redundant timer** | Fire the wake-up twice, and once against a terminal aggregate → no extra external calls. |
| **Close boundary** | Event timed to land while a run is persisting-and-closing → processed by that run or a fresh one, never dropped. |
| **Partial arrivals** | 4-member aggregate, 3 members across days 3/20/50 → no revert, deadline still the original instant, neither extended nor reset. |
| **All-at-once** | Every member in one batch → `applyEffect` still fires (rule 10). |
| **Deadline expiry** | Advance past the deadline, fire the wake-up → `revertEffect` once, terminal. |
| **Late arrival** | A member after the terminal outcome → run loads terminal state, calls nothing. |
| **Break after target met** | Reach the target, then remove a required member → reverts once. |
| **Stale event** | Replay an old event with a lower version → dropped, state unchanged. |
| **Reload fidelity** | A run started by the 5th event reconstructs what the 4th persisted, including per-member versions. |
| **Wedged activity** | Make a per-member activity fail permanently → the member is parked, siblings still process (rule 11). |
| **Handler discipline** | No `@SignalMethod` calls an activity or awaits. |

## See also

- [Temporal workflows](temporal-workflows.md) — the producer/consumer pipeline implemented in this repo.
- [Concurrency & virtual threads](concurrency-and-virtual-threads.md) — bounded concurrency within a
  single workflow.
