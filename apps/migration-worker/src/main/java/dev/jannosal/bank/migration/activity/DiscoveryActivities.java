package dev.jannosal.bank.migration.activity;

import dev.jannosal.bank.migration.api.CustomerPage;
import io.temporal.activity.ActivityInterface;

@ActivityInterface
public interface DiscoveryActivities {

    /** Fetch one page of entries with the given product-directory reference and return the distinct customer ids on it. */
    CustomerPage fetchCustomerPage(String productDirectoryReference, int offset, int limit);
}
