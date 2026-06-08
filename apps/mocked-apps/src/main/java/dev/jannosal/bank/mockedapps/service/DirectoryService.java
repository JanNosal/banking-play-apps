package dev.jannosal.bank.mockedapps.service;

import dev.jannosal.bank.mockedapps.persistence.DirectoryEntryDocument;
import dev.jannosal.bank.mockedapps.persistence.DirectoryEntryRepository;
import dev.jannosal.bank.product.model.DirectoryEntry;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

/** Reads and writes the legacy Customer Product and Service Directory. */
@Service
public class DirectoryService {

    private final DirectoryEntryRepository repository;

    public DirectoryService(DirectoryEntryRepository repository) {
        this.repository = repository;
    }

    /** Offset/limit query with optional product-directory and customer filters; returns the matching page. */
    public Page<DirectoryEntryDocument> query(String productDirectoryReference, String customerId, int offset, int limit) {
        Pageable pageable = PageRequest.of(offset / limit, limit);
        boolean hasRef = StringUtils.hasText(productDirectoryReference);
        boolean hasCustomer = StringUtils.hasText(customerId);

        if (hasRef && hasCustomer) {
            return repository.findByCustomerIdAndProductDirectoryReference(customerId, productDirectoryReference, pageable);
        }
        if (hasCustomer) {
            return repository.findByCustomerId(customerId, pageable);
        }
        if (hasRef) {
            return repository.findByProductDirectoryReference(productDirectoryReference, pageable);
        }
        return repository.findAll(pageable);
    }

    public DirectoryEntry get(String id) {
        return repository.findById(id)
                .map(DirectoryEntryDocument::getEntry)
                .orElseThrow(() -> new NotFoundException("Directory entry not found: " + id));
    }

    /** Create or replace by id (idempotent upsert). */
    public DirectoryEntry save(DirectoryEntry entry) {
        repository.save(new DirectoryEntryDocument(entry));
        return entry;
    }

    public List<DirectoryEntry> saveAll(List<DirectoryEntry> entries) {
        repository.saveAll(entries.stream().map(DirectoryEntryDocument::new).toList());
        return entries;
    }

    public DirectoryEntry update(String id, DirectoryEntry entry) {
        if (!repository.existsById(id)) {
            throw new NotFoundException("Directory entry not found: " + id);
        }
        // Force the path id onto the stored payload so the document and its @Id stay consistent.
        DirectoryEntry withId = new DirectoryEntry(
                id, entry.href(), entry.directoryEntryDescription(), entry.directoryEntryStatus(),
                entry.directoryEntryDate(), entry.customerReference(), entry.servicerReference(),
                entry.productDirectoryReference(), entry.product(), entry.service(), entry.productAgreement());
        repository.save(new DirectoryEntryDocument(withId));
        return withId;
    }

    public void delete(String id) {
        if (!repository.existsById(id)) {
            throw new NotFoundException("Directory entry not found: " + id);
        }
        repository.deleteById(id);
    }

    public long count() {
        return repository.count();
    }
}
