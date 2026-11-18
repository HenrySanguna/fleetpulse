package dev.fleetpulse.api.config;

import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@ConfigurationProperties(prefix = "fleetpulse.reports.activity")
@Validated
public record FleetpulseActivityReportProperties(@Positive @DefaultValue("31") int maxRangeDays) {
}
