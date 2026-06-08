package dev.jannosal.bank.e2e;

import dev.jannosal.bank.migration.client.HttpClients;
import dev.jannosal.bank.migration.client.LegacyInventoryClient;
import dev.jannosal.bank.product.model.DirectoryEntry;
import dev.jannosal.bank.product.model.ProductDirectory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.ComposeContainer;
import org.testcontainers.containers.wait.strategy.Wait;

import java.io.File;
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
 * Dockerised end-to-end test: brings up the <b>real</b> stack from docker-compose (MongoDB, Temporal
 * server, and the three app <b>containers</b>) via Testcontainers {@link ComposeContainer}, drives the
 * migration purely over HTTP, and asserts the new inventory equals the migrated subset of the legacy
 * one. This is the most faithful test; it is slower (it builds the app images) and needs Docker Engine
 * 25+ (Testcontainers 2.0 negotiates Docker API 1.44).
 *
 * <p>Gated behind {@code -De2e.dockerised=true} so the default {@code -Pit} run uses the fast in-JVM
 * {@link FullMigrationE2EIT}. Enable explicitly:
 * <pre>./mvnw -Pit -pl e2e-tests verify -De2e.dockerised=true -Dit.test=DockerisedStackE2EIT</pre>
 */
@EnabledIfSystemProperty(named = "e2e.dockerised", matches = "true")
class DockerisedStackE2EIT {

    private static final ParameterizedTypeReference<List<DirectoryEntry>> PRODUCT_LIST =
            new ParameterizedTypeReference<>() {};

    // The compose stack publishes fixed host ports (8085/8086/8087), so we connect to localhost.
    private static final String LEGACY = "http://localhost:8085";
    private static final String TARGET = "http://localhost:8086";
    private static final String WORKER = "http://localhost:8087";

    static final ComposeContainer STACK = new ComposeContainer(
            new File("../docker-compose.yml"),
            new File("../infra/docker-compose.test.yml"))
            .withBuild(true)                                   // build the app images from source
            .withExposedService("temporal", 7233, Wait.forListeningPort())
            .withStartupTimeout(Duration.ofMinutes(8));        // first run compiles + builds 3 images

    static LegacyInventoryClient legacy;
    static RestClient target;
    static RestClient worker;

    @BeforeAll
    static void up() {
        STACK.start();
        legacy = new LegacyInventoryClient(HttpClients.restClient(LEGACY));
        target = HttpClients.restClient(TARGET);
        worker = HttpClients.restClient(WORKER);
        // Wait — bounded — for the apps to be healthy before driving them.
        await("apps healthy").atMost(Duration.ofMinutes(2)).pollInterval(Duration.ofSeconds(2))
                .ignoreExceptions()
                .until(() -> health(LEGACY) && health(TARGET) && health(WORKER));
    }

    @AfterAll
    static void down() {
        STACK.stop();
    }

    @Test
    @Timeout(value = 600, unit = TimeUnit.SECONDS)
    void newInventoryEqualsMigratedSubsetOfLegacy() {
        Set<String> migratedCustomers = distinctCustomersWithParentSpec();
        assertThat(migratedCustomers).isNotEmpty();

        Map<String, DirectoryEntry> expected = new HashMap<>();
        for (String c : migratedCustomers) {
            expected.putAll(fetchLegacyPackage(c));
        }

        // The worker auto-started discovery(maxPasses=1) + the endless loader. Poll its progress query.
        await("all customers processed").atMost(Duration.ofSeconds(240)).pollInterval(Duration.ofSeconds(1))
                .until(() -> processedCustomers() >= migratedCustomers.size());

        // Interrupt the endless loader, then wait — bounded — for the target to settle.
        worker.post().uri("/migration/stop").retrieve().toBodilessEntity();
        await("target settled").atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofSeconds(1))
                .until(() -> targetTotal() == expected.size());

        assertThat(targetTotal()).isEqualTo(expected.size());
        for (String c : migratedCustomers) {
            assertThat(fetchTargetPackage(c)).as("package for %s", c).isEqualTo(fetchLegacyPackage(c));
        }
        for (int i = 0; i < 40; i++) {
            String c = String.format("CUST-%06d", i);
            if (!migratedCustomers.contains(c)) {
                assertThat(targetCountForCustomer(c)).as("non-Premier customer %s absent", c).isZero();
            }
        }
    }

    // ---- helpers ----------------------------------------------------------------------------------

    private static boolean health(String base) {
        return HttpClients.restClient(base).get().uri("/actuator/health")
                .retrieve().toBodilessEntity().getStatusCode().is2xxSuccessful();
    }

    private long processedCustomers() {
        Map<?, ?> s = worker.get().uri("/migration/status").retrieve().body(Map.class);
        return ((Number) s.get("processedCustomers")).longValue();
    }

    @SuppressWarnings("unchecked")
    private long targetTotal() {
        Map<String, Object> s = target.get().uri("/customer-product-and-service-directory/stats").retrieve().body(Map.class);
        return ((Number) s.get("entries")).longValue();
    }

    private Set<String> distinctCustomersWithParentSpec() {
        Set<String> customers = new LinkedHashSet<>();
        int offset = 0;
        int limit = 100;
        while (true) {
            LegacyInventoryClient.Page page = legacy.queryByProductDirectoryReference(ProductDirectory.PREMIER_BANKING, offset, limit);
            page.entries().forEach(p -> { if (p.customerId() != null) customers.add(p.customerId()); });
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
            LegacyInventoryClient.Page page = legacy.queryByCustomer(customerId, offset, limit);
            page.entries().forEach(p -> result.put(p.id(), p));
            if (!page.hasMore(offset, limit)) {
                break;
            }
            offset += limit;
        }
        return result;
    }

    private Map<String, DirectoryEntry> fetchTargetPackage(String customerId) {
        Map<String, DirectoryEntry> result = new HashMap<>();
        int offset = 0;
        int limit = 200;
        while (true) {
            int currentOffset = offset;
            ResponseEntity<List<DirectoryEntry>> response = target.get()
                    .uri(b -> b.path("/customer-product-and-service-directory")
                            .queryParam("customerReference", customerId)
                            .queryParam("offset", currentOffset)
                            .queryParam("limit", limit)
                            .build())
                    .retrieve().toEntity(PRODUCT_LIST);
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

    private long targetCountForCustomer(String customerId) {
        ResponseEntity<List<DirectoryEntry>> response = target.get()
                .uri(b -> b.path("/customer-product-and-service-directory")
                        .queryParam("customerReference", customerId)
                        .queryParam("offset", 0)
                        .queryParam("limit", 1)
                        .build())
                .retrieve().toEntity(PRODUCT_LIST);
        return Long.parseLong(response.getHeaders().getFirst("X-Total-Count"));
    }
}
