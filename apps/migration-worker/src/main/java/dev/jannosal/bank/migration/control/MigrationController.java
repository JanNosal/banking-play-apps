package dev.jannosal.bank.migration.control;

import dev.jannosal.bank.migration.api.LoaderStats;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/migration")
public class MigrationController {

    private final MigrationService service;

    public MigrationController(MigrationService service) {
        this.service = service;
    }

    @PostMapping("/start")
    public Map<String, String> start() {
        return Map.of("status", "started", "workflows", service.start());
    }

    @PostMapping("/stop")
    public Map<String, String> stop() {
        service.stop();
        return Map.of("status", "stop-requested");
    }

    @GetMapping("/status")
    public LoaderStats status() {
        return service.status();
    }
}
