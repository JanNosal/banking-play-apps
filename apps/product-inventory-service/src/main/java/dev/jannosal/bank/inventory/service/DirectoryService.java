package dev.jannosal.bank.inventory.service;

import dev.jannosal.bank.inventory.persistence.DirectoryEntryDocument;
import dev.jannosal.bank.inventory.persistence.DirectoryEntryRepository;
import dev.jannosal.bank.product.model.DirectoryEntry;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

/** Reads and writes the modern Customer Product and Service Directory (the migration target). */
@Service
public class DirectoryService {

    private final DirectoryEntryRepository repository;

    public DirectoryService(DirectoryEntryRepository repository) {
        this.repository = repository;
    }

    /** Idempotent upsert by id — safe to call repeatedly (Temporal activity retries, re-runs). */
    public DirectoryEntry upsert(DirectoryEntry entry) {
        repository.save(new DirectoryEntryDocument(entry));
        return entry;
    }

    public List<DirectoryEntry> upsertAll(List<DirectoryEntry> entries) {
        repository.saveAll(entries.stream().map(DirectoryEntryDocument::new).toList());
        return entries;
    }

    public DirectoryEntry get(String id) {
        return repository.findById(id)
                .map(DirectoryEntryDocument::getEntry)
                .orElseThrow(() -> new NotFoundException("Directory entry not found: " + id));
    }

    public Page<DirectoryEntryDocument> query(String customerId, int offset, int limit) {
        Pageable pageable = PageRequest.of(offset / limit, limit);
        if (StringUtils.hasText(customerId)) {
            return repository.findByCustomerId(customerId, pageable);
        }
        return repository.findAll(pageable);
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

    public long countByCustomer(String customerId) {
        return repository.countByCustomerId(customerId);
    }
}
