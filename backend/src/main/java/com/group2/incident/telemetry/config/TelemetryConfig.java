package com.group2.incident.telemetry.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(TelemetryProperties.class)
public class TelemetryConfig {
}
