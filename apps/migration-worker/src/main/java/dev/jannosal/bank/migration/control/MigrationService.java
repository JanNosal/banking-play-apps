package dev.jannosal.bank.migration.control;

import dev.jannosal.bank.migration.api.DiscoveryParams;
import dev.jannosal.bank.migration.api.LoaderParams;
import dev.jannosal.bank.migration.api.LoaderStats;
import dev.jannosal.bank.migration.config.MigrationProperties;
import dev.jannosal.bank.migration.workflow.CustomerLoaderWorkflow;
import dev.jannosal.bank.migration.workflow.DiscoveryWorkflow;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowExecutionAlreadyStarted;
import io.temporal.client.WorkflowOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Starts/stops/queries the migration workflows. The loader is a singleton (fixed workflow id) started
 * <b>before</b> discovery so discovery's signals always reach a live workflow.
 */
@Service
public class MigrationService {

    private static final Logger log = LoggerFactory.getLogger(MigrationService.class);

    private final WorkflowClient client;
    private final MigrationProperties props;

    public MigrationService(WorkflowClient client, MigrationProperties props) {
        this.client = client;
        this.props = props;
    }

    /** Idempotently start the loader, then (re)start a discovery run. Returns a short status string. */
    public String start() {
        startLoaderIfAbsent();
        startDiscovery();
        return "loader=" + props.loaderWorkflowId() + ", discovery=" + props.discoveryWorkflowId();
    }

    private void startLoaderIfAbsent() {
        CustomerLoaderWorkflow loader = client.newWorkflowStub(
                CustomerLoaderWorkflow.class,
                WorkflowOptions.newBuilder()
                        .setTaskQueue(props.taskQueue())
                        .setWorkflowId(props.loaderWorkflowId())
                        .build());
        LoaderParams params = new LoaderParams(
                props.maxConcurrency(), props.loaderPageSize(), props.drainAndExit(),
                props.continueAsNewAfter(), null);
        try {
            WorkflowClient.start(loader::run, params);
            log.info("Started loader workflow id={}", props.loaderWorkflowId());
        } catch (WorkflowExecutionAlreadyStarted e) {
            log.info("Loader workflow id={} already running — reusing it", props.loaderWorkflowId());
        }
    }

    private void startDiscovery() {
        DiscoveryWorkflow discovery = client.newWorkflowStub(
                DiscoveryWorkflow.class,
                WorkflowOptions.newBuilder()
                        .setTaskQueue(props.taskQueue())
                        .setWorkflowId(props.discoveryWorkflowId())
                        .build());
        DiscoveryParams params = new DiscoveryParams(
                props.productDirectoryReference(), props.discoveryPageSize(), props.maxPasses(),
                props.interPassSleepSeconds(), props.loaderWorkflowId(), 0);
        try {
            WorkflowClient.start(discovery::discover, params);
            log.info("Started discovery workflow id={} productDirectoryReference={}", props.discoveryWorkflowId(), props.productDirectoryReference());
        } catch (WorkflowExecutionAlreadyStarted e) {
            log.info("Discovery workflow id={} already running", props.discoveryWorkflowId());
        }
    }

    /** Cooperative stop signal to the loader (the production "interrupt"). */
    public void stop() {
        loaderStub().requestStop();
        log.info("Sent requestStop to loader id={}", props.loaderWorkflowId());
    }

    public LoaderStats status() {
        return loaderStub().stats();
    }

    private CustomerLoaderWorkflow loaderStub() {
        return client.newWorkflowStub(CustomerLoaderWorkflow.class, props.loaderWorkflowId());
    }
}
