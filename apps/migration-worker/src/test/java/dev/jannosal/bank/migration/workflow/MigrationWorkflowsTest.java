package dev.jannosal.bank.migration.workflow;

import dev.jannosal.bank.migration.activity.DiscoveryActivities;
import dev.jannosal.bank.migration.activity.LoaderActivities;
import dev.jannosal.bank.migration.api.CustomerLoadResult;
import dev.jannosal.bank.migration.api.CustomerPage;
import dev.jannosal.bank.migration.api.DiscoveryParams;
import dev.jannosal.bank.migration.api.LoaderParams;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Fast, in-memory test of the two workflows with <b>mocked activities</b> — no MongoDB, no HTTP, no
 * Temporal server. It proves: (1) discovery paginates to the last page and stops, (2) duplicate
 * customer ids are deduplicated (loadCustomer runs once per distinct customer), and (3) drain-and-exit
 * mode lets the loader terminate. Every wait is bounded so the test can never hang.
 */
class MigrationWorkflowsTest {

    private static final String TASK_QUEUE = "test-migration";
    private static final String LOADER_ID = "loader-under-test";

    private TestWorkflowEnvironment env;
    private DiscoveryActivities discoveryActivities;
    private LoaderActivities loaderActivities;

    @BeforeEach
    void setUp() {
        env = TestWorkflowEnvironment.newInstance();
        Worker worker = env.newWorker(TASK_QUEUE);
        worker.registerWorkflowImplementationTypes(DiscoveryWorkflowImpl.class, CustomerLoaderWorkflowImpl.class);

        discoveryActivities = mock(DiscoveryActivities.class);
        loaderActivities = mock(LoaderActivities.class);
        worker.registerActivitiesImplementations(discoveryActivities, loaderActivities);

        env.start();
    }

    @AfterEach
    void tearDown() {
        env.close();
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS) // belt-and-braces: the test JVM is killed if it hangs
    void migratesEachDistinctCustomerExactlyOnce() throws java.util.concurrent.TimeoutException {
        // Two pages; the second repeats c2 (dedup must collapse it). Distinct: c0..c4.
        when(discoveryActivities.fetchCustomerPage("PD-PREMIER-BANKING", 0, 3))
                .thenReturn(new CustomerPage(List.of("c0", "c1", "c2"), 5, true));
        when(discoveryActivities.fetchCustomerPage("PD-PREMIER-BANKING", 3, 3))
                .thenReturn(new CustomerPage(List.of("c2", "c3", "c4"), 5, false));
        when(loaderActivities.loadCustomer(anyString(), anyInt()))
                .thenAnswer(inv -> new CustomerLoadResult(inv.getArgument(0), 5, 1));

        WorkflowClient client = env.getWorkflowClient();

        // Loader first (drain-and-exit so it terminates once discovery completes and the queue drains).
        CustomerLoaderWorkflow loader = client.newWorkflowStub(
                CustomerLoaderWorkflow.class,
                WorkflowOptions.newBuilder().setTaskQueue(TASK_QUEUE).setWorkflowId(LOADER_ID).build());
        WorkflowClient.start(loader::run, new LoaderParams(4, 50, true, 0, null));

        // Discovery: a single bounded pass.
        DiscoveryWorkflow discovery = client.newWorkflowStub(
                DiscoveryWorkflow.class,
                WorkflowOptions.newBuilder().setTaskQueue(TASK_QUEUE).setWorkflowId("discovery-under-test").build());
        WorkflowClient.start(discovery::discover,
                new DiscoveryParams("PD-PREMIER-BANKING", 3, 1, 0, LOADER_ID, 0));

        // Bounded wait for the loader to finish — never an unbounded block.
        WorkflowStub.fromTyped(loader).getResult(20, TimeUnit.SECONDS, Void.class);

        verify(loaderActivities, times(5)).loadCustomer(anyString(), anyInt());
        verify(loaderActivities).loadCustomer("c0", 50);
        verify(loaderActivities).loadCustomer("c4", 50);
        assertThat(loader.stats().processedCustomers()).isEqualTo(5);
        assertThat(loader.stats().loadedEntries()).isEqualTo(25);
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void requestStopEndsTheLoaderEvenWithoutDiscovery() throws java.util.concurrent.TimeoutException {
        when(loaderActivities.loadCustomer(anyString(), anyInt()))
                .thenAnswer(inv -> new CustomerLoadResult(inv.getArgument(0), 1, 1));

        WorkflowClient client = env.getWorkflowClient();
        CustomerLoaderWorkflow loader = client.newWorkflowStub(
                CustomerLoaderWorkflow.class,
                WorkflowOptions.newBuilder().setTaskQueue(TASK_QUEUE).setWorkflowId(LOADER_ID).build());
        // Not drain-and-exit: this models the production endless loop; only requestStop ends it.
        WorkflowClient.start(loader::run, new LoaderParams(4, 50, false, 0, null));

        loader.enqueueCustomer("c0");
        loader.enqueueCustomer("c1");
        loader.requestStop();

        WorkflowStub.fromTyped(loader).getResult(20, TimeUnit.SECONDS, Void.class);
        assertThat(loader.stats().stopRequested()).isTrue();
    }
}
