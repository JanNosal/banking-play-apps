package dev.jannosal.bank.inventory.persistence;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface DirectoryEntryRepository extends MongoRepository<DirectoryEntryDocument, String> {

    Page<DirectoryEntryDocument> findByCustomerId(String customerId, Pageable pageable);

    long countByCustomerId(String customerId);
}
