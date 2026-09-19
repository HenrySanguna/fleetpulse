package dev.fleetpulse.api.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

// The console (Cloudflare Pages) and the api (its own host/VM) are always
// different origins -- there is no same-origin dev proxy config either -- so
// this has been a real, previously-unexercised gap: no @Bean here means
// Spring Security adds no CORS headers at all, and a real browser silently
// refuses to let the console read (or, depending on the request, even send)
// any cross-origin response, regardless of credentials being otherwise
// valid. Defaulted here (not @Validated/@NotBlank, not required from env)
// since these values aren't secret and localhost:4200/the known Pages
// project name are safe, stable defaults for every environment this project
// actually deploys to.
@ConfigurationProperties(prefix = "fleetpulse.cors")
public record FleetpulseCorsProperties(List<String> allowedOriginPatterns) {
}
