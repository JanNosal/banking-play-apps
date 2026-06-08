package dev.jannosal.bank.migration.workflow;

import dev.jannosal.bank.migration.activity.LoaderActivities;
import dev.jannosal.bank.migration.api.CustomerLoadResult;
import dev.jannosal.bank.migration.api.LoaderParams;
import dev.jannosal.bank.migration.api.LoaderStats;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.workflow.Async;
import io.temporal.workflow.Promise;
import io.temporal.workflow.Workflow;
import org.slf4j.Logger;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Drains the customer queue with bounded concurrency. The queue is an ordered, deduplicating FIFO
 * ({@link LinkedHashSet}): re-signalling a still-queued customer is a no-op, but a customer that was
 * already loaded can be re-enqueued on a later discovery pass so updates are picked up. Up to
 * {@code maxConcurrency} customers load in parallel (each a {@code loadCustomer} activity that itself
 * fans out over virtual threads). In production the workflow never ends; it {@code continueAsNew}s
 * after {@code continueAsNewAfter} customers to keep history bounded. Tests end it via
 * {@link #requestStop()} or drain-and-exit mode.
 */
public class CustomerLoaderWorkflowImpl implements CustomerLoaderWorkflow {

    private static final Logger log = Workflow.getLogger(CustomerLoaderWorkflowImpl.class);

    private final LoaderActivities activities = Workflow.newActivityStub(
            LoaderActivities.class,
            ActivityOptions.newBuilder()
                    .setStartToCloseTimeout(Duration.ofMinutes(5))
                    .setHeartbeatTimeout(Duration.ofSeconds(30))
                    .setRetryOptions(RetryOptions.newBuilder()
                            .setInitialInterval(Duration.ofSeconds(1))
                            .setBackoffCoefficient(2.0)
                            .setMaximumInterval(Duration.ofSeconds(30))
                            .setMaximumAttempts(5)
                            .build())
                    .build());

    /** Ordered, deduplicating FIFO of customers waiting to be loaded. */
    private final LinkedHashSet<String> pending = new LinkedHashSet<>();
    /** Customers currently being loaded (for the stats query). */
    private final Set<String> inFlight = new HashSet<>();

    private boolean stopRequested = false;
    private boolean passComplete = false;
    private long processedCustomers = 0;
    private long loadedEntries = 0;

    private record Running(String customerId, Promise<CustomerLoadResult> promise) {}

    @Override
    public void run(LoaderParams params) {
        if (params.initialPending() != null) {
            pending.addAll(params.initialPending());
        }
        int max = Math.max(1, params.maxConcurrency());
        List<Running> running = new ArrayList<>();

        while (true) {
            // 1. Bound history in production: continue-as-new carrying the still-pending queue.
            if (params.continueAsNewAfter() > 0 && processedCustomers >= params.continueAsNewAfter()) {
                drainAll(running);
                Workflow.continueAsNew(params.withInitialPending(new ArrayList<>(pending)));
            }

            // 2. Cooperative stop: let in-flight loads finish (keep the new inventory consistent), exit.
            if (stopRequested) {
                drainAll(running);
                break;
            }

            // 3. Batch/test drain-and-exit: discovery is done and everything is processed.
            if (params.drainAndExitWhenComplete() && passComplete && pending.isEmpty() && running.isEmpty()) {
                break;
            }

            // 4. Fill up to the concurrency limit from the queue.
            while (running.size() < max && !pending.isEmpty()) {
                String customerId = pollFirst();
                inFlight.add(customerId);
                running.add(new Running(customerId,
                        Async.function(activities::loadCustomer, customerId, params.pageSize())));
            }

            // 5. Nothing running: block until a new customer arrives, a stop is requested, or
            //    (drain mode) discovery signals completion. Never a busy-wait.
            if (running.isEmpty()) {
                Workflow.await(() -> stopRequested
                        || !pending.isEmpty()
                        || (params.drainAndExitWhenComplete() && passComplete));
                continue;
            }

            // 6. Wait until at least one in-flight load finishes (or a stop arrives).
            Workflow.await(() -> stopRequested || anyCompleted(running));

            // 7. Reap everything that has completed (non-blocking).
            reapCompleted(running);
        }

        log.info("Loader exiting: processedCustomers={} loadedEntries={} stopRequested={}",
                processedCustomers, loadedEntries, stopRequested);
    }

    private String pollFirst() {
        Iterator<String> it = pending.iterator();
        String first = it.next();
        it.remove();
        return first;
    }

    private boolean anyCompleted(List<Running> running) {
        for (Running r : running) {
            if (r.promise().isCompleted()) {
                return true;
            }
        }
        return false;
    }

    /** Remove and account for every completed promise (does not block on the rest). */
    private void reapCompleted(List<Running> running) {
        Iterator<Running> it = running.iterator();
        while (it.hasNext()) {
            Running r = it.next();
            if (r.promise().isCompleted()) {
                account(r.promise().get());
                inFlight.remove(r.customerId());
                it.remove();
            }
        }
    }

    /** Block until all in-flight loads complete, accounting for each; clears the list. */
    private void drainAll(List<Running> running) {
        for (Running r : running) {
            account(r.promise().get());
            inFlight.remove(r.customerId());
        }
        running.clear();
    }

    private void account(CustomerLoadResult result) {
        processedCustomers++;
        loadedEntries += result.entriesLoaded();
    }

    @Override
    public void enqueueCustomer(String customerId) {
        if (customerId != null && !customerId.isBlank()) {
            pending.add(customerId); // LinkedHashSet dedups while preserving FIFO order
        }
    }

    @Override
    public void discoveryPassComplete() {
        passComplete = true;
    }

    @Override
    public void requestStop() {
        stopRequested = true;
    }

    @Override
    public LoaderStats stats() {
        return new LoaderStats(pending.size(), inFlight.size(), processedCustomers, loadedEntries,
                stopRequested, passComplete);
    }
}
