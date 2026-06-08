package dev.jannosal.bank.mockedapps;

import dev.jannosal.bank.mockedapps.seed.SeedProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/** Mock of the legacy banking Customer Product and Service Directory (BIAN) — source for the migration. */
@SpringBootApplication
@EnableConfigurationProperties(SeedProperties.class)
public class MockedAppsApplication {

    public static void main(String[] args) {
        SpringApplication.run(MockedAppsApplication.class, args);
    }
}
