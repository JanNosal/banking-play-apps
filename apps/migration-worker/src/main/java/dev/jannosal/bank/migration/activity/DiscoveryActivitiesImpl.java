package dev.jannosal.bank.migration.activity;

import dev.jannosal.bank.migration.api.CustomerPage;
import dev.jannosal.bank.migration.client.LegacyInventoryClient;
import dev.jannosal.bank.product.model.DirectoryEntry;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class DiscoveryActivitiesImpl implements DiscoveryActivities {

    private final LegacyInventoryClient legacy;

    public DiscoveryActivitiesImpl(LegacyInventoryClient legacy) {
        this.legacy = legacy;
    }

    @Override
    public CustomerPage fetchCustomerPage(String productDirectoryReference, int offset, int limit) {
        LegacyInventoryClient.Page page = legacy.queryByProductDirectoryReference(productDirectoryReference, offset, limit);
        // Dedup within the page (preserve order); the workflow dedups across pages.
        Set<String> customers = new LinkedHashSet<>();
        for (DirectoryEntry e : page.entries()) {
            String customerId = e.customerId();
            if (customerId != null) {
                customers.add(customerId);
            }
        }
        return new CustomerPage(List.copyOf(customers), page.total(), page.hasMore(offset, limit));
    }
}
