package dev.jannosal.bank.migration.config;

import dev.jannosal.bank.migration.activity.DiscoveryActivitiesImpl;
import dev.jannosal.bank.migration.activity.LoaderActivitiesImpl;
import dev.jannosal.bank.migration.client.HttpClients;
import dev.jannosal.bank.migration.client.LegacyInventoryClient;
import dev.jannosal.bank.migration.client.NewInventoryClient;
import dev.jannosal.bank.migration.workflow.CustomerLoaderWorkflowImpl;
import dev.jannosal.bank.migration.workflow.DiscoveryWorkflowImpl;
import io.temporal.client.WorkflowClient;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;
import io.temporal.worker.WorkerOptions;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Wires the Temporal worker and registers both workflows and both activity implementations on a single
 * task queue. The {@link WorkflowClient} comes from the Temporal Spring Boot starter, but under Spring
 * Boot 4 that starter does not publish a {@link WorkerFactory} bean — so we build the factory from the
 * client here and start it once the worker is registered.
 */
@Configuration
public class WorkerConfig {

    @Bean
    public WorkerFactory temporalWorkerFactory(WorkflowClient client) {
        return WorkerFactory.newInstance(client);
    }

    @Bean
    public LegacyInventoryClient legacyInventoryClient(MigrationProperties props) {
        return new LegacyInventoryClient(HttpClients.restClient(
                props.legacyBaseUrl(),
                Duration.ofSeconds(props.httpConnectTimeoutSeconds()),
                Duration.ofSeconds(props.httpReadTimeoutSeconds())));
    }

    @Bean
    public NewInventoryClient newInventoryClient(MigrationProperties props) {
        return new NewInventoryClient(HttpClients.restClient(
                props.inventoryBaseUrl(),
                Duration.ofSeconds(props.httpConnectTimeoutSeconds()),
                Duration.ofSeconds(props.httpReadTimeoutSeconds())));
    }

    @Bean
    public DiscoveryActivitiesImpl discoveryActivities(LegacyInventoryClient legacy) {
        return new DiscoveryActivitiesImpl(legacy);
    }

    @Bean
    public LoaderActivitiesImpl loaderActivities(LegacyInventoryClient legacy, NewInventoryClient newInventory) {
        return new LoaderActivitiesImpl(legacy, newInventory);
    }

    @Bean
    public Worker migrationWorker(WorkerFactory workerFactory,
                                  MigrationProperties props,
                                  DiscoveryActivitiesImpl discoveryActivities,
                                  LoaderActivitiesImpl loaderActivities) {
        WorkerOptions options = WorkerOptions.newBuilder()
                // Allow the loader to keep up to maxConcurrency customer loads in flight at once.
                .setMaxConcurrentActivityExecutionSize(Math.max(64, props.maxConcurrency() * 2))
                // Run activity tasks on virtual threads: each concurrent customer load is a virtual
                // thread, and inside it the page-fetch/upsert fan-out spawns more (see LoaderActivitiesImpl).
                .setUsingVirtualThreadsOnActivityWorker(true)
                .build();
        Worker worker = workerFactory.newWorker(props.taskQueue(), options);
        worker.registerWorkflowImplementationTypes(DiscoveryWorkflowImpl.class, CustomerLoaderWorkflowImpl.class);
        worker.registerActivitiesImplementations(discoveryActivities, loaderActivities);
        return worker;
    }

    /**
     * Start the factory only after every worker bean has been instantiated and its workflow/activity
     * types registered ({@code afterSingletonsInstantiated} runs after all singletons are created and
     * before any {@code ApplicationRunner}, so discovery/loader polling is live before the migration
     * is kicked off).
     */
    @Bean
    public SmartInitializingSingleton temporalWorkerFactoryStarter(WorkerFactory factory) {
        return factory::start;
    }
}
