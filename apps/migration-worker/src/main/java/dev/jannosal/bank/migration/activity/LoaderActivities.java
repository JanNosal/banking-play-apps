package dev.jannosal.bank.migration.activity;

import dev.jannosal.bank.migration.api.CustomerLoadResult;
import io.temporal.activity.ActivityInterface;

@ActivityInterface
public interface LoaderActivities {

    /** Fetch a customer's full directory from the legacy core and upsert it into the new one. */
    CustomerLoadResult loadCustomer(String customerId, int pageSize);
}
