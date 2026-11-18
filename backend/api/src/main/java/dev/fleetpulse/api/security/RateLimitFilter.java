package dev.fleetpulse.api.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import dev.fleetpulse.api.config.FleetpulseRateLimitProperties;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

// Keyed on getRemoteAddr() only: X-Forwarded-For is client-controlled, and in
// production the forwarded-headers strategy already rewrites the remote
// address from the trusted proxy.
@Component
public class RateLimitFilter extends OncePerRequestFilter {

    private enum Category { LOGIN, MQTT_CREDENTIALS, API }

    private final FleetpulseRateLimitProperties properties;
    private final Cache<String, Bucket> buckets;

    public RateLimitFilter(FleetpulseRateLimitProperties properties) {
        this.properties = properties;
        this.buckets = Caffeine.newBuilder()
            .maximumSize(properties.maxTrackedClients())
            .expireAfterAccess(properties.clientIdleExpiry())
            .build();
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        Category category = categoryOf(request);
        if (category == null) {
            filterChain.doFilter(request, response);
            return;
        }
        Bucket bucket = buckets.get(category + ":" + request.getRemoteAddr(), key -> newBucket(category));
        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
        if (probe.isConsumed()) {
            filterChain.doFilter(request, response);
            return;
        }
        // setStatus, not sendError: the error dispatch to /error is itself
        // authenticated and would turn this 429 into a 401.
        long retryAfterSeconds = Math.max(1, TimeUnit.NANOSECONDS.toSeconds(probe.getNanosToWaitForRefill()) + 1);
        response.setHeader("Retry-After", Long.toString(retryAfterSeconds));
        response.setStatus(429);
    }

    private Category categoryOf(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if ("/login".equals(path) && "POST".equals(request.getMethod())) {
            return Category.LOGIN;
        }
        if ("/api/mqtt/credentials".equals(path)) {
            return Category.MQTT_CREDENTIALS;
        }
        if (path.startsWith("/api/")) {
            return Category.API;
        }
        return null;
    }

    private Bucket newBucket(Category category) {
        int perMinute = switch (category) {
            case LOGIN -> properties.loginPerMinute();
            case MQTT_CREDENTIALS -> properties.mqttCredentialsPerMinute();
            case API -> properties.apiPerMinute();
        };
        return Bucket.builder()
            .addLimit(Bandwidth.builder().capacity(perMinute).refillIntervally(perMinute, Duration.ofMinutes(1)).build())
            .build();
    }
}
