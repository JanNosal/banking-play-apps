package dev.jannosal.bank.migration;

import dev.jannosal.bank.migration.config.MigrationProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/** Temporal worker hosting the discovery and loader workflows for the banking product migration. */
@SpringBootApplication
@EnableConfigurationProperties(MigrationProperties.class)
public class MigrationWorkerApplication {

    public static void main(String[] args) {
        SpringApplication.run(MigrationWorkerApplication.class, args);
    }
}
