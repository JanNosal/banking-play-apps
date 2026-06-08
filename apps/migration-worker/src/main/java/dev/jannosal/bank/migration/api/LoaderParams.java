package dev.jannosal.bank.migration.api;

import java.util.List;

/**
 * Input to {@code CustomerLoaderWorkflow}.
 *
 * <ul>
 *   <li>{@code maxConcurrency} — how many customers are loaded in parallel (bounded fan-out).</li>
 *   <li>{@code drainAndExitWhenComplete} — test/batch mode: once discovery signals a pass complete and
 *       the queue drains, the workflow finishes (so a test can await its result). Production sets this
 *       {@code false} so the loader runs forever.</li>
 *   <li>{@code continueAsNewAfter} — after processing this many customers the workflow continues-as-new
 *       (carrying the still-pending queue) to keep its history small. {@code 0} disables compaction.</li>
 *   <li>{@code initialPending} — queue carried over from a previous continue-as-new generation.</li>
 * </ul>
 */
public record LoaderParams(
        int maxConcurrency,
        int pageSize,
        boolean drainAndExitWhenComplete,
        long continueAsNewAfter,
        List<String> initialPending
) {
    public LoaderParams withInitialPending(List<String> pending) {
        return new LoaderParams(maxConcurrency, pageSize, drainAndExitWhenComplete, continueAsNewAfter, pending);
    }
}
