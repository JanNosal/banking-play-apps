package dev.jannosal.bank.migration.workflow;

import dev.jannosal.bank.migration.activity.DiscoveryActivities;
import dev.jannosal.bank.migration.api.CustomerPage;
import dev.jannosal.bank.migration.api.DiscoveryParams;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.workflow.Workflow;
import org.slf4j.Logger;

import java.time.Duration;
import java.util.LinkedHashSet;

/**
 * Scans the legacy inventory by product-directory reference, page by page, and streams each <b>new</b>
 * customer id (deduplicated within the pass by an ordered {@link LinkedHashSet}) as a signal to the
 * loader. Pagination tells it when a pass ends. In production ({@code maxPasses < 0}) it sleeps then
 * {@code continueAsNew}s — re-scanning forever to pick up changes while keeping history bounded. With
 * a positive {@code maxPasses} it stops, which is what tests use to make the run finite.
 */
public class DiscoveryWorkflowImpl implements DiscoveryWorkflow {

    private static final Logger log = Workflow.getLogger(DiscoveryWorkflowImpl.class);

    private final DiscoveryActivities activities = Workflow.newActivityStub(
            DiscoveryActivities.class,
            ActivityOptions.newBuilder()
                    .setStartToCloseTimeout(Duration.ofMinutes(2))
                    .setRetryOptions(RetryOptions.newBuilder()
                            .setInitialInterval(Duration.ofSeconds(1))
                            .setBackoffCoefficient(2.0)
                            .setMaximumInterval(Duration.ofSeconds(30))
                            .setMaximumAttempts(5)
                            .build())
                    .build());

    @Override
    public void discover(DiscoveryParams params) {
        CustomerLoaderWorkflow loader =
                Workflow.newExternalWorkflowStub(CustomerLoaderWorkflow.class, params.loaderWorkflowId());

        LinkedHashSet<String> seen = new LinkedHashSet<>();
        int offset = 0;
        while (true) {
            CustomerPage page = activities.fetchCustomerPage(params.productDirectoryReference(), offset, params.pageSize());
            for (String customerId : page.customerIds()) {
                if (seen.add(customerId)) {
                    loader.enqueueCustomer(customerId);
                }
            }
            if (!page.hasMore()) {
                break;
            }
            offset += params.pageSize();
        }

        log.info("Discovery pass {} complete: {} distinct customers enqueued", params.passNumber(), seen.size());
        loader.discoveryPassComplete();

        long nextPass = params.passNumber() + 1;
        if (params.maxPasses() >= 0 && nextPass >= params.maxPasses()) {
            return; // bounded run — stop here (tests rely on this)
        }
        if (params.interPassSleepSeconds() > 0) {
            Workflow.sleep(Duration.ofSeconds(params.interPassSleepSeconds()));
        }
        // Restart with a fresh, small history for the next pass (endless production loop).
        Workflow.continueAsNew(params.withPassNumber(nextPass));
    }
}
