package dev.jannosal.bank.migration.control;

import dev.jannosal.bank.migration.config.MigrationProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/** Optionally kicks off the migration on boot (docker sets {@code migration.auto-start=true}). */
@Component
public class MigrationStarter implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(MigrationStarter.class);

    private final MigrationService service;
    private final MigrationProperties props;

    public MigrationStarter(MigrationService service, MigrationProperties props) {
        this.service = service;
        this.props = props;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (props.autoStart()) {
            log.info("migration.auto-start=true — starting migration workflows");
            service.start();
        } else {
            log.info("migration.auto-start=false — POST /migration/start to begin");
        }
    }
}
