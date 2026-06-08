package dev.jannosal.bank.product.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * BIAN {@code Involvedparty} — a reference to a party (customer, servicer) involved with a directory
 * entry. The party with role {@code Customer} is what the migration keys on: discovery extracts it
 * from each matching entry; the loader queries the legacy directory by it. {@code partyName} and
 * {@code partyRole} are kept beyond BIAN's bare reference pair for readability in this sandbox.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record Involvedparty(
        String partyReference,
        String partyName,
        String partyRole,
        String involvementReference
) {}
