package dev.fleetpulse.api.security;

import dev.fleetpulse.api.config.FleetpulseRateLimitProperties;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class RateLimitFilterTest {

    private final FilterChain chain = mock(FilterChain.class);
    private final RateLimitFilter filter = new RateLimitFilter(
        new FleetpulseRateLimitProperties(3, 2, 5, 100, Duration.ofMinutes(10)));

    private MockHttpServletResponse call(String method, String uri, String remoteAddr) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        request.setRemoteAddr(remoteAddr);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        return response;
    }

    @Test
    void rejectsLoginAttemptsBeyondTheLimitWithRetryAfter() throws Exception {
        for (int i = 0; i < 3; i++) {
            assertThat(call("POST", "/login", "10.0.0.1").getStatus()).isEqualTo(200);
        }

        MockHttpServletResponse rejected = call("POST", "/login", "10.0.0.1");

        assertThat(rejected.getStatus()).isEqualTo(429);
        assertThat(Long.parseLong(rejected.getHeader("Retry-After"))).isPositive();
        verify(chain, times(3)).doFilter(any(), any());
    }

    @Test
    void limitsEachClientAddressIndependently() throws Exception {
        for (int i = 0; i < 3; i++) {
            call("POST", "/login", "10.0.0.1");
        }

        assertThat(call("POST", "/login", "10.0.0.1").getStatus()).isEqualTo(429);
        assertThat(call("POST", "/login", "10.0.0.2").getStatus()).isEqualTo(200);
    }

    @Test
    void ignoresForwardedForHeaderWhenKeyingClients() throws Exception {
        for (int i = 0; i < 3; i++) {
            MockHttpServletRequest request = new MockHttpServletRequest("POST", "/login");
            request.setRemoteAddr("10.0.0.1");
            request.addHeader("X-Forwarded-For", "9.9.9." + i);
            filter.doFilter(request, new MockHttpServletResponse(), chain);
        }

        assertThat(call("POST", "/login", "10.0.0.1").getStatus()).isEqualTo(429);
    }

    @Test
    void appliesTheStricterMqttCredentialsLimit() throws Exception {
        assertThat(call("GET", "/api/mqtt/credentials", "10.0.0.1").getStatus()).isEqualTo(200);
        assertThat(call("GET", "/api/mqtt/credentials", "10.0.0.1").getStatus()).isEqualTo(200);
        assertThat(call("GET", "/api/mqtt/credentials", "10.0.0.1").getStatus()).isEqualTo(429);
    }

    @Test
    void appliesTheGeneralApiLimitToOtherApiPaths() throws Exception {
        for (int i = 0; i < 5; i++) {
            assertThat(call("GET", "/api/vehicles", "10.0.0.1").getStatus()).isEqualTo(200);
        }

        assertThat(call("GET", "/api/vehicles", "10.0.0.1").getStatus()).isEqualTo(429);
    }

    @Test
    void keepsSeparateBucketsPerCategory() throws Exception {
        for (int i = 0; i < 5; i++) {
            call("GET", "/api/vehicles", "10.0.0.1");
        }

        assertThat(call("POST", "/login", "10.0.0.1").getStatus()).isEqualTo(200);
    }

    @Test
    void neverLimitsActuatorOrNonApiPaths() throws Exception {
        for (int i = 0; i < 20; i++) {
            assertThat(call("GET", "/actuator/health", "10.0.0.1").getStatus()).isEqualTo(200);
            assertThat(call("GET", "/v3/api-docs", "10.0.0.1").getStatus()).isEqualTo(200);
        }
    }
}
