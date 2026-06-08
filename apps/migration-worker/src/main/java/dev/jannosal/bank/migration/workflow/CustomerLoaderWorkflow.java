package dev.jannosal.bank.migration.workflow;

import dev.jannosal.bank.migration.api.LoaderParams;
import dev.jannosal.bank.migration.api.LoaderStats;
import io.temporal.workflow.QueryMethod;
import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/**
 * Long-running, singleton workflow that owns the customer queue. Discovery streams customer ids in via
 * {@link #enqueueCustomer}; the workflow drains them with bounded concurrency. {@link #requestStop} is
 * the cooperative interrupt the test environment uses to end the otherwise endless loop.
 */
@WorkflowInterface
public interface CustomerLoaderWorkflow {

    @WorkflowMethod
    void run(LoaderParams params);

    /** Add a customer to the FIFO/dedup queue (delivered as a signal from discovery). */
    @SignalMethod
    void enqueueCustomer(String customerId);

    /** Discovery finished a full pass — lets drain-and-exit (batch/test) mode terminate cleanly. */
    @SignalMethod
    void discoveryPassComplete();

    /** Cooperative stop: finish in-flight loads, then exit. The test's interrupt mechanism. */
    @SignalMethod
    void requestStop();

    /** Progress snapshot used by ops dashboards and by the test to know when the load is done. */
    @QueryMethod
    LoaderStats stats();
}
