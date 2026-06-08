package dev.jannosal.bank.migration.client;

import dev.jannosal.bank.product.model.DirectoryEntry;
import org.springframework.web.client.RestClient;

/**
 * Writes into the modern Customer Product and Service Directory
 * ({@code /customer-product-and-service-directory}); upsert is idempotent.
 */
public class NewInventoryClient {

    private final RestClient http;

    public NewInventoryClient(RestClient http) {
        this.http = http;
    }

    public void upsert(DirectoryEntry entry) {
        http.post()
                .uri("/customer-product-and-service-directory")
                .body(entry)
                .retrieve()
                .toBodilessEntity();
    }
}
