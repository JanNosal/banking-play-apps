package dev.jannosal.bank.product.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * BIAN {@code ProductFeature} — a single facet of a {@link Product}. Used both for plain attributes
 * (e.g. {@code AccountNumber}=…, {@code InterestRate}=…) and for the backing resources a product
 * relies on (e.g. {@code LedgerAccount}, {@code Iban}, {@code Card}), whose identifier is carried in
 * {@link #productFeatureIdentification()}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record ProductFeature(
        String productFeatureType,
        String productFeatureName,
        String productFeatureSpecification,
        String productFeatureIdentification
) {}
