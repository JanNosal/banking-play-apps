package dev.jannosal.bank.mockedapps.web;

import dev.jannosal.bank.mockedapps.persistence.DirectoryEntryDocument;
import dev.jannosal.bank.mockedapps.service.DirectoryService;
import dev.jannosal.bank.product.model.DirectoryEntry;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.List;

/**
 * Customer Product and Service Directory API as exposed by the <b>legacy</b> core. Mounted under
 * {@code /customer-product-and-service-directory}. Filtering uses {@code productDirectoryReference} and
 * {@code customerReference} with {@code offset}/{@code limit} pagination; the total count is returned
 * in {@code X-Total-Count} so a client can detect the last page (offset + limit >= total).
 */
@RestController
@RequestMapping("/customer-product-and-service-directory")
public class LegacyCustomerProductDirectoryController {

    private final DirectoryService service;

    public LegacyCustomerProductDirectoryController(DirectoryService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<List<DirectoryEntry>> listEntries(
            @RequestParam(name = "productDirectoryReference", required = false) String productDirectoryReference,
            @RequestParam(name = "customerReference", required = false) String customerId,
            @RequestParam(defaultValue = "0") @Min(0) int offset,
            @RequestParam(defaultValue = "50") @Min(1) @Max(500) int limit) {

        Page<DirectoryEntryDocument> page = service.query(productDirectoryReference, customerId, offset, limit);
        List<DirectoryEntry> entries = page.getContent().stream().map(DirectoryEntryDocument::getEntry).toList();
        return ResponseEntity.ok()
                .header("X-Total-Count", String.valueOf(page.getTotalElements()))
                .header("X-Result-Count", String.valueOf(entries.size()))
                .body(entries);
    }

    @GetMapping("/{id}")
    public DirectoryEntry getEntry(@PathVariable String id) {
        return service.get(id);
    }

    @PostMapping
    public ResponseEntity<DirectoryEntry> createEntry(@RequestBody DirectoryEntry entry) {
        DirectoryEntry saved = service.save(entry);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(saved.id())
                .toUri();
        return ResponseEntity.created(location).body(saved);
    }

    @PatchMapping("/{id}")
    public DirectoryEntry updateEntry(@PathVariable String id, @RequestBody DirectoryEntry entry) {
        return service.update(id, entry);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteEntry(@PathVariable String id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }
}
