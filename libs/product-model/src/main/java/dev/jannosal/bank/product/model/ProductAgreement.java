package dev.jannosal.bank.product.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * BIAN {@code ProductAgreement} — the agreement under which a {@link DirectoryEntry} is held.
 * {@link #productAgreementType()} carries a BIAN {@code Productagreementtypevalues} value (e.g.
 * {@code CurrentAccountAgreement}, {@code ConsumerLoanAgreement}, {@code TermDepositAgreement}). It
 * folds in the pricing/term that the legacy TMF model carried as {@code productPrice} and
 * {@code productTerm}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record ProductAgreement(
        String productAgreementType,
        String agreementStatus,
        String agreementSignedDate,
        ProductFee productFee,
        AgreementValidityPeriod agreementValidityPeriod
) {
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ProductFee(String feeType, String recurringFeePeriod, Money feeAmount) {}

    /** BIAN-style amount/currency pair (cf. {@code CurrencyAndAmount}). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Money(String amount, String currency) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AgreementValidityPeriod(String fromDateTime, String toDateTime) {}
}
