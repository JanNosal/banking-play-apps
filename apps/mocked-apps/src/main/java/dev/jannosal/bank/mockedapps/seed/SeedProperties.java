package dev.jannosal.bank.mockedapps.seed;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Controls dataset generation. The same generator produces the huge docker dataset and the tiny,
 * deterministic dataset used by the end-to-end test — only these numbers differ. A fixed
 * {@code randomSeed} makes generation fully reproducible so tests can assert exact counts.
 */
@ConfigurationProperties(prefix = "seed")
public record SeedProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("true") boolean reset,
        @DefaultValue("1500") int customers,
        @DefaultValue("42") long randomSeed,
        @DefaultValue("2000") int batchSize
) {}
