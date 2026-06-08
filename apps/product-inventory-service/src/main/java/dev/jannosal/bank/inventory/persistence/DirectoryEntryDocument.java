package dev.jannosal.bank.inventory.persistence;

import dev.jannosal.bank.product.model.DirectoryEntry;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/** MongoDB wrapper around a {@link DirectoryEntry}; mirrors the legacy core's storage shape. */
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
