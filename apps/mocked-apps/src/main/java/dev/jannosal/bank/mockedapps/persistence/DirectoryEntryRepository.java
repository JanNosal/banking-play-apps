package dev.jannosal.bank.mockedapps.persistence;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface DirectoryEntryRepository extends MongoRepository<DirectoryEntryDocument, String> {

    /** All entries carrying a given product-directory reference (e.g. PD-PREMIER-BANKING). Drives discovery. */
    Page<DirectoryEntryDocument> findByProductDirectoryReference(String productDirectoryReference, Pageable pageable);

    /** All entries belonging to a customer (their full directory). Drives the per-customer load. */
    Page<DirectoryEntryDocument> findByCustomerId(String customerId, Pageable pageable);

    /** Entries of a customer filtered to a given product-directory reference — combined query. */
    Page<DirectoryEntryDocument> findByCustomerIdAndProductDirectoryReference(
            String customerId, String productDirectoryReference, Pageable pageable);
}
