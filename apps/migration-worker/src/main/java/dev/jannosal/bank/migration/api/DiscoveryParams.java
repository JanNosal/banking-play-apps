package dev.jannosal.bank.migration.api;

/**
 * Input to {@code DiscoveryWorkflow}. {@code maxPasses < 0} means run forever (production), re-scanning
 * the legacy directory pass after pass to pick up changes; a positive value bounds the run (tests).
 * {@code passNumber} is carried across continue-as-new so the workflow knows when to stop.
 */
public record DiscoveryParams(
        String productDirectoryReference,
        int pageSize,
        long maxPasses,
        long interPassSleepSeconds,
        String loaderWorkflowId,
        long passNumber
) {
    public DiscoveryParams withPassNumber(long next) {
        return new DiscoveryParams(productDirectoryReference, pageSize, maxPasses, interPassSleepSeconds, loaderWorkflowId, next);
    }
}
