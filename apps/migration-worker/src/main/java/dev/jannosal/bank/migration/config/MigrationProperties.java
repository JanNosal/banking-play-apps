package dev.jannosal.bank.migration.config;

import dev.jannosal.bank.product.model.ProductDirectory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * All migration tuning in one place. The defaults are production defaults (endless loop:
 * {@code maxPasses = -1}, {@code drainAndExit = false}); tests override the few values that make the
 * run finite and fast.
 */
@ConfigurationProperties(prefix = "migration")
public record MigrationProperties(
        @DefaultValue("http://localhost:8085") String legacyBaseUrl,
        @DefaultValue("http://localhost:8086") String inventoryBaseUrl,
        @DefaultValue(ProductDirectory.PREMIER_BANKING) String productDirectoryReference,
        @DefaultValue("product-migration") String taskQueue,
        @DefaultValue("customer-loader-main") String loaderWorkflowId,
        @DefaultValue("discovery-main") String discoveryWorkflowId,
        @DefaultValue("200") int discoveryPageSize,
        @DefaultValue("100") int loaderPageSize,
        @DefaultValue("16") int maxConcurrency,
        @DefaultValue("-1") long maxPasses,
        @DefaultValue("30") long interPassSleepSeconds,
        @DefaultValue("500") long continueAsNewAfter,
        @DefaultValue("false") boolean drainAndExit,
        @DefaultValue("false") boolean autoStart,
        @DefaultValue("5") long httpConnectTimeoutSeconds,
        @DefaultValue("30") long httpReadTimeoutSeconds
) {}
