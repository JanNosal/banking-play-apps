package dev.jannosal.bank.migration.workflow;

import dev.jannosal.bank.migration.api.DiscoveryParams;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

@WorkflowInterface
public interface DiscoveryWorkflow {

    @WorkflowMethod
    void discover(DiscoveryParams params);
}
