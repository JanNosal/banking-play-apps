package dev.jannosal.bank.mockedapps.web;

import dev.jannosal.bank.mockedapps.seed.DataSeeder;
import dev.jannosal.bank.mockedapps.service.DirectoryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Operational helpers: total directory-entry count and an on-demand reseed (used by ops and tests). */
@RestController
@RequestMapping("/admin")
public class AdminController {

    private final DirectoryService service;
    private final DataSeeder seeder;

    public AdminController(DirectoryService service, DataSeeder seeder) {
        this.service = service;
        this.seeder = seeder;
    }

    @GetMapping("/stats")
    public Map<String, Object> stats() {
        return Map.of("entries", service.count());
    }

    @PostMapping("/seed")
    public DataSeeder.SeedStats reseed() {
        return seeder.seed();
    }
}
