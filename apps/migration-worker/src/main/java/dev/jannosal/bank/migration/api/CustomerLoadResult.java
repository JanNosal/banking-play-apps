package dev.jannosal.bank.migration.api;

/** Result of loading one customer's full directory into the new core. */
public record CustomerLoadResult(String customerId, int entriesLoaded, int pagesFetched) {}
