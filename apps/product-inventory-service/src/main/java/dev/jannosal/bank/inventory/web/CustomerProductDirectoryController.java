package dev.jannosal.bank.inventory.web;

import dev.jannosal.bank.inventory.persistence.DirectoryEntryDocument;
import dev.jannosal.bank.inventory.service.DirectoryService;
import dev.jannosal.bank.product.model.DirectoryEntry;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.List;
import java.util.Map;

/**
 * Customer Product and Service Directory API of the <b>modern</b> core. Mounted under
 * {@code /customer-product-and-service-directory}. {@code POST} performs an idempotent upsert by id so
 * the migration worker can retry without creating duplicates.
 */
@RestController
@RequestMapping("/customer-product-and-service-directory")
public class CustomerProductDirectoryController {

    private final DirectoryService service;

    public CustomerProductDirectoryController(DirectoryService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<DirectoryEntry> upsertEntry(@RequestBody DirectoryEntry entry) {
        DirectoryEntry saved = service.upsert(entry);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(saved.id())
                .toUri();
        return ResponseEntity.created(location).body(saved);
    }

    @GetMapping("/{id}")
    public DirectoryEntry getEntry(@PathVariable String id) {
        return service.get(id);
    }

    @GetMapping
    public ResponseEntity<List<DirectoryEntry>> listEntries(
            @RequestParam(name = "customerReference", required = false) String customerId,
            @RequestParam(defaultValue = "0") @Min(0) int offset,
            @RequestParam(defaultValue = "50") @Min(1) @Max(500) int limit) {

        Page<DirectoryEntryDocument> page = service.query(customerId, offset, limit);
        List<DirectoryEntry> entries = page.getContent().stream().map(DirectoryEntryDocument::getEntry).toList();
        return ResponseEntity.ok()
                .header("X-Total-Count", String.valueOf(page.getTotalElements()))
                .body(entries);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteEntry(@PathVariable String id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/stats")
    public Map<String, Object> stats() {
        return Map.of("entries", service.count());
    }
}
