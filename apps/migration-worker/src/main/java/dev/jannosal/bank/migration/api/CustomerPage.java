package dev.jannosal.bank.migration.api;

import java.util.List;

/** One page of the discovery scan: the customer ids found on this page plus paging info. */
public record CustomerPage(List<String> customerIds, long total, boolean hasMore) {}
