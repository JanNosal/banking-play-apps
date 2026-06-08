package dev.jannosal.bank.migration.api;

/** Queryable snapshot of the loader's progress — the test polls this to know when to stop. */
public record LoaderStats(
        int pending,
        int inFlight,
        long processedCustomers,
        long loadedEntries,
        boolean stopRequested,
        boolean passComplete
) {}
