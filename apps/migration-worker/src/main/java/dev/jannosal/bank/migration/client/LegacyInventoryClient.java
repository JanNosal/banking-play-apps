package dev.jannosal.bank.migration.client;

import dev.jannosal.bank.product.model.DirectoryEntry;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

import java.util.List;

/**
 * Reads the legacy Customer Product and Service Directory
 * ({@code /customer-product-and-service-directory}) with offset/limit paging.
 */
public class LegacyInventoryClient {

    private static final ParameterizedTypeReference<List<DirectoryEntry>> ENTRY_LIST =
            new ParameterizedTypeReference<>() {};

    private final RestClient http;

    public LegacyInventoryClient(RestClient http) {
        this.http = http;
    }

    /** A page of entries plus the server-reported total (from {@code X-Total-Count}). */
    public record Page(List<DirectoryEntry> entries, long total) {
        public boolean hasMore(int offset, int limit) {
            return (long) offset + limit < total;
        }
    }

    public Page queryByProductDirectoryReference(String productDirectoryReference, int offset, int limit) {
        return query("productDirectoryReference", productDirectoryReference, offset, limit);
    }

    public Page queryByCustomer(String customerId, int offset, int limit) {
        return query("customerReference", customerId, offset, limit);
    }

    private Page query(String filterName, String filterValue, int offset, int limit) {
        ResponseEntity<List<DirectoryEntry>> response = http.get()
                .uri(b -> b.path("/customer-product-and-service-directory")
                        .queryParam(filterName, filterValue)
                        .queryParam("offset", offset)
                        .queryParam("limit", limit)
                        .build())
                .retrieve()
                .toEntity(ENTRY_LIST);

        List<DirectoryEntry> body = response.getBody() == null ? List.of() : response.getBody();
        long total = parseTotal(response, body.size());
        return new Page(body, total);
    }

    private long parseTotal(ResponseEntity<?> response, int fallback) {
        String header = response.getHeaders().getFirst("X-Total-Count");
        if (header == null) {
            return fallback;
        }
        try {
            return Long.parseLong(header);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
