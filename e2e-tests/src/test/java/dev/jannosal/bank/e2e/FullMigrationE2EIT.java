package dev.jannosal.bank.e2e;

import dev.jannosal.bank.mockedapps.MockedAppsApplication;
import dev.jannosal.bank.inventory.ProductInventoryApplication;
import dev.jannosal.bank.migration.activity.DiscoveryActivitiesImpl;
import dev.jannosal.bank.migration.activity.LoaderActivitiesImpl;
import dev.jannosal.bank.migration.api.DiscoveryParams;
import dev.jannosal.bank.migration.api.LoaderParams;
import dev.jannosal.bank.migration.client.HttpClients;
import dev.jannosal.bank.migration.client.LegacyInventoryClient;
import dev.jannosal.bank.migration.client.NewInventoryClient;
import dev.jannosal.bank.migration.workflow.CustomerLoaderWorkflow;
import dev.jannosal.bank.migration.workflow.CustomerLoaderWorkflowImpl;
import dev.jannosal.bank.migration.workflow.DiscoveryWorkflow;
import dev.jannosal.bank.migration.workflow.DiscoveryWorkflowImpl;
import dev.jannosal.bank.product.model.DirectoryEntry;
import dev.jannosal.bank.product.model.ProductDirectory;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.testing.TestEnvironmentOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.MongoDBContainer;

import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * End-to-end proof that the migration copies the legacy data faithfully and selectively.
 *
 * <p>Topology, all in one JVM: a real MongoDB (Testcontainers) with two databases; the legacy
 * mock and the modern inventory booted as real Spring Boot servlet apps on random ports; and the
 * real discovery + loader workflows driven by Temporal's in-memory test server with the real activity
 * implementations (which make real HTTP calls to the two apps).
 *
 * <p>Reliability rules applied throughout (see {@code .github/copilot-instructions.md}): time-skipping is OFF so real
 * activities are not killed by a skipped clock; the test stops the endless loop with
 * {@code requestStop}; every wait is bounded by {@code Awaitility} and a hard {@code @Timeout}, so the
 * test fails fast instead of hanging.
 */
class FullMigrationE2EIT {

    private static final int CUSTOMERS = 40;
    private static final long SEED = 7L;
    private static final String TASK_QUEUE = "e2e-migration";
    private static final String LOADER_ID = "e2e-loader";
    private static final ParameterizedTypeReference<List<DirectoryEntry>> PRODUCT_LIST =
            new ParameterizedTypeReference<>() {};

    // Normally a Testcontainers-managed MongoDB. If -De2e.mongo.uri=mongodb://host:port is given, an
    // externally-managed MongoDB is used instead (handy on machines whose Docker is too old for the
    // Testcontainers client). Exactly one of these is active.
    static MongoDBContainer mongo;
    static String legacyMongoUri;
    static String newMongoUri;

    static ConfigurableApplicationContext mockedCtx;
    static ConfigurableApplicationContext inventoryCtx;
    static TestWorkflowEnvironment temporal;

    static LegacyInventoryClient legacyClient;
    static RestClient newHttp;

    @BeforeAll
    static void bootEverything() {
        String externalUri = System.getProperty("e2e.mongo.uri");
        if (externalUri != null && !externalUri.isBlank()) {
            legacyMongoUri = externalUri + "/legacy_inventory";
            newMongoUri = externalUri + "/new_inventory";
        } else {
            mongo = new MongoDBContainer("mongo:7.0");
            mongo.start();
            legacyMongoUri = mongo.getReplicaSetUrl("legacy_inventory");
            newMongoUri = mongo.getReplicaSetUrl("new_inventory");
        }

        // Pass as command-line args (highest precedence) so they override each app's application.yml.
        mockedCtx = new SpringApplicationBuilder(MockedAppsApplication.class).run(
                "--server.port=0",
                "--spring.mongodb.uri=" + legacyMongoUri,
                "--spring.data.mongodb.auto-index-creation=true",
                "--seed.enabled=true",
                "--seed.reset=true",
                "--seed.customers=" + CUSTOMERS,
                "--seed.random-seed=" + SEED);

        inventoryCtx = new SpringApplicationBuilder(ProductInventoryApplication.class).run(
                "--server.port=0",
                "--spring.mongodb.uri=" + newMongoUri,
                "--spring.data.mongodb.auto-index-creation=true");

        String legacyBaseUrl = "http://localhost:" + port(mockedCtx);
        String inventoryBaseUrl = "http://localhost:" + port(inventoryCtx);

        legacyClient = new LegacyInventoryClient(HttpClients.restClient(legacyBaseUrl));
        newHttp = HttpClients.restClient(inventoryBaseUrl);

        temporal = TestWorkflowEnvironment.newInstance(
                TestEnvironmentOptions.newBuilder().setUseTimeskipping(false).build());
        Worker worker = temporal.newWorker(TASK_QUEUE);
        worker.registerWorkflowImplementationTypes(DiscoveryWorkflowImpl.class, CustomerLoaderWorkflowImpl.class);
        worker.registerActivitiesImplementations(
                new DiscoveryActivitiesImpl(legacyClient),
                new LoaderActivitiesImpl(legacyClient, new NewInventoryClient(newHttp)));
        temporal.start();
    }

    @AfterAll
    static void shutdown() {
        if (temporal != null) temporal.close();
        if (inventoryCtx != null) inventoryCtx.close();
        if (mockedCtx != null) mockedCtx.close();
        if (mongo != null) mongo.stop();
    }

    @Test
    @Timeout(value = 180, unit = TimeUnit.SECONDS) // hard ceiling — the test is killed rather than hanging
    void newInventoryEqualsMigratedSubsetOfLegacy() throws Exception {
        // ---- expectation derived from the legacy data itself --------------------------------------
        Set<String> migratedCustomers = distinctCustomersWithParentSpec();
        assertThat(migratedCustomers).as("seed must produce some Premier customers").isNotEmpty();

        Map<String, DirectoryEntry> expected = new HashMap<>();
        for (String customerId : migratedCustomers) {
            expected.putAll(fetchLegacyPackage(customerId));
        }

        // ---- run the real workflows ----------------------------------------------------------------
        WorkflowClient client = temporal.getWorkflowClient();
        CustomerLoaderWorkflow loader = client.newWorkflowStub(
                CustomerLoaderWorkflow.class,
                WorkflowOptions.newBuilder().setTaskQueue(TASK_QUEUE).setWorkflowId(LOADER_ID).build());
        // Production-shaped loader (endless: drainAndExit=false). Small page size to exercise paging.
        WorkflowClient.start(loader::run, new LoaderParams(8, 5, false, 0, null));

        DiscoveryWorkflow discovery = client.newWorkflowStub(
                DiscoveryWorkflow.class,
                WorkflowOptions.newBuilder().setTaskQueue(TASK_QUEUE).setWorkflowId("e2e-discovery").build());
        WorkflowClient.start(discovery::discover,
                new DiscoveryParams(ProductDirectory.PREMIER_BANKING, 7, 1, 0, LOADER_ID, 0));

        // ---- bounded wait until every discovered customer has been loaded --------------------------
        await("all customers processed")
                .atMost(Duration.ofSeconds(120))
                .pollInterval(Duration.ofMillis(200))
                .until(() -> loader.stats().processedCustomers() >= migratedCustomers.size());

        // Interrupt the endless loop (the production "stop"); then wait — bounded — for it to finish.
        loader.requestStop();
        WorkflowStub.fromTyped(loader).getResult(30, TimeUnit.SECONDS, Void.class);

        // ---- assert data parity --------------------------------------------------------------------
        assertThat(loader.stats().loadedEntries())
                .as("loader reported product count")
                .isEqualTo(expected.size());
        assertThat(newInventoryTotal())
                .as("new inventory total == migrated legacy products")
                .isEqualTo(expected.size());

        for (String customerId : migratedCustomers) {
            Map<String, DirectoryEntry> legacy = fetchLegacyPackage(customerId);
            Map<String, DirectoryEntry> migrated = fetchNewPackage(customerId);
            assertThat(migrated)
                    .as("package for customer %s must match legacy exactly", customerId)
                    .isEqualTo(legacy);
        }

        // ---- assert selectivity: customers without a Premier bundle must NOT be migrated --------------
        for (int i = 0; i < CUSTOMERS; i++) {
            String customerId = String.format("CUST-%06d", i);
            if (!migratedCustomers.contains(customerId)) {
                assertThat(newTotalForCustomer(customerId))
                        .as("non-Premier customer %s must be absent from the new inventory", customerId)
                        .isZero();
            }
        }
    }

    // ---- helpers ----------------------------------------------------------------------------------

    private Set<String> distinctCustomersWithParentSpec() {
        Set<String> customers = new LinkedHashSet<>();
        int offset = 0;
        int limit = 100;
        while (true) {
            LegacyInventoryClient.Page page = legacyClient.queryByProductDirectoryReference(ProductDirectory.PREMIER_BANKING, offset, limit);
            for (DirectoryEntry p : page.entries()) {
                if (p.customerId() != null) {
                    customers.add(p.customerId());
                }
            }
            if (!page.hasMore(offset, limit)) {
                break;
            }
            offset += limit;
        }
        return customers;
    }

    private Map<String, DirectoryEntry> fetchLegacyPackage(String customerId) {
        Map<String, DirectoryEntry> result = new HashMap<>();
        int offset = 0;
        int limit = 200;
        while (true) {
            LegacyInventoryClient.Page page = legacyClient.queryByCustomer(customerId, offset, limit);
            page.entries().forEach(p -> result.put(p.id(), p));
            if (!page.hasMore(offset, limit)) {
                break;
            }
            offset += limit;
        }
        return result;
    }

    private Map<String, DirectoryEntry> fetchNewPackage(String customerId) {
        Map<String, DirectoryEntry> result = new HashMap<>();
        int offset = 0;
        int limit = 200;
        while (true) {
            int currentOffset = offset;
            ResponseEntity<List<DirectoryEntry>> response = newHttp.get()
                    .uri(b -> b.path("/customer-product-and-service-directory")
                            .queryParam("customerReference", customerId)
                            .queryParam("offset", currentOffset)
                            .queryParam("limit", limit)
                            .build())
                    .retrieve()
                    .toEntity(PRODUCT_LIST);
            List<DirectoryEntry> body = response.getBody() == null ? List.of() : response.getBody();
            body.forEach(p -> result.put(p.id(), p));
            long total = Long.parseLong(response.getHeaders().getFirst("X-Total-Count"));
            if ((long) offset + limit >= total) {
                break;
            }
            offset += limit;
        }
        return result;
    }

    private long newTotalForCustomer(String customerId) {
        ResponseEntity<List<DirectoryEntry>> response = newHttp.get()
                .uri(b -> b.path("/customer-product-and-service-directory")
                        .queryParam("customerReference", customerId)
                        .queryParam("offset", 0)
                        .queryParam("limit", 1)
                        .build())
                .retrieve()
                .toEntity(PRODUCT_LIST);
        return Long.parseLong(response.getHeaders().getFirst("X-Total-Count"));
    }

    @SuppressWarnings("unchecked")
    private long newInventoryTotal() {
        Map<String, Object> stats = newHttp.get().uri("/customer-product-and-service-directory/stats")
                .retrieve().body(Map.class);
        return ((Number) stats.get("entries")).longValue();
    }

    private static int port(ConfigurableApplicationContext ctx) {
        return ((WebServerApplicationContext) ctx).getWebServer().getPort();
    }
}
