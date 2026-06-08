package dev.jannosal.bank.mockedapps.persistence;

import dev.jannosal.bank.product.model.DirectoryEntry;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * MongoDB wrapper around a {@link DirectoryEntry}. The full entry is stored as a nested sub-document
 * under {@code entry}; {@code customerId} and {@code productDirectoryReference} are denormalised to the
 * top level and indexed so the two hot queries — "all entries of a customer" and "all entries with a
 * given product-directory reference" — are served by indexes rather than collection scans.
 */
@Document("directoryEntry")
public class DirectoryEntryDocument {

    @Id
    private String id;

    @Indexed
    private String customerId;

    @Indexed
    private String productDirectoryReference;

    private DirectoryEntry entry;

    public DirectoryEntryDocument() {
    }

    public DirectoryEntryDocument(DirectoryEntry entry) {
        this.id = entry.id();
        this.customerId = entry.customerId();
        this.productDirectoryReference = entry.productDirectoryReference();
        this.entry = entry;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getCustomerId() {
        return customerId;
    }

    public void setCustomerId(String customerId) {
        this.customerId = customerId;
    }

    public String getProductDirectoryReference() {
        return productDirectoryReference;
    }

    public void setProductDirectoryReference(String productDirectoryReference) {
        this.productDirectoryReference = productDirectoryReference;
    }

    public DirectoryEntry getEntry() {
        return entry;
    }

    public void setEntry(DirectoryEntry entry) {
        this.entry = entry;
    }
}
