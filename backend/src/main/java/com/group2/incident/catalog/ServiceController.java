package com.group2.incident.catalog;

import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/services")
public class ServiceController {

    @PostMapping
    public String createService() {
        return "Service created with SLA targets (SEV1: 15m, SEV2: 60m, SEV3: 240m, SEV4: 1440m)";
    }

    @GetMapping
    public List<String> listServices() {
        return List.of("Payments", "Authentication");
    }
}