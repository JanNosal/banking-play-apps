package dev.jannosal.bank.product.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * BIAN {@code Service} behaviour qualifier — a service that supports a {@link DirectoryEntry}
 * (e.g. a payments service, a statements service). {@link #serviceType()} carries a BIAN
 * {@code Servicetypevalues} value such as {@code FinancialService} or {@code InformationService}.
 * Replaces the legacy TMF {@code backingService}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record Service(
        String serviceInstanceReference,
        String serviceName,
        String serviceType,
        String serviceLifecycleStatus
) {}
