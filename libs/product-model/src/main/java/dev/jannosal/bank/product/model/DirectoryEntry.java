package dev.jannosal.bank.product.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * A <b>Customer Product and Service Directory Entry</b> (BIAN) — one product or service that a
 * customer holds (a current account, savings account, debit card, overdraft, loan, …). It is the
 * control-record instance maintained by the BIAN <i>Customer Product and Service Directory</i> service
 * domain, and the unit the migration copies: the discovery scan finds customers by
 * {@link #productDirectoryReference()} and the loader moves each customer's whole set of entries.
 *
 * <p>Modelled after BIAN's {@code CustomerProductAndServiceDirectoryEntry} but trimmed to the fields
 * this sandbox needs. The same record is the REST contract on both the legacy and the modern core, and
 * the payload exchanged by the migration worker. Persistence stores it as a nested sub-document and
 * denormalises {@code customerReference.partyReference} and {@code productDirectoryReference} to indexed
 * top-level fields so the two hot queries (by customer, by product-directory reference) hit indexes.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record DirectoryEntry(
        String directoryEntryInstanceReference,
        String href,
        String directoryEntryDescription,
        String directoryEntryStatus,
        String directoryEntryDate,
        Involvedparty customerReference,
        Involvedparty servicerReference,
        String productDirectoryReference,
        Product product,
        List<Service> service,
        ProductAgreement productAgreement
) {
    /** Convenience: this entry's instance reference (its id). */
    public String id() {
        return directoryEntryInstanceReference;
    }

    /** The id of the customer this entry belongs to, or {@code null} if none. */
    public String customerId() {
        return customerReference == null ? null : customerReference.partyReference();
    }
}
