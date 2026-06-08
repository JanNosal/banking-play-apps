package dev.jannosal.bank.product.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * BIAN {@code Product} behaviour qualifier — describes the product held by a
 * {@link DirectoryEntry}. {@link #productType()} carries a BIAN {@code Bankingproducttypevalues}
 * value (e.g. {@code CurrentAccountProduct}, {@code SavingAccountProduct}, {@code LoanProduct}) and
 * {@link #productFeature()} folds in the per-product facets (account number, IBAN, ledger account,
 * card, interest rate, …) that the legacy TMF model carried as {@code characteristic} and
 * {@code backingResource}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record Product(
        String productInstanceReference,
        String productName,
        String productDescription,
        String productType,
        String productLifecycleStatus,
        String productSerialNumber,
        List<ProductFeature> productFeature
) {}
