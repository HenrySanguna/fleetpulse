package dev.fleetpulse.api.security;

import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

// Shared by the dispatcher-session integration tests (REFACTOR step): logs
// in over /login and turns the resulting Set-Cookie header into a header set
// a later request can replay, since TestRestTemplate has no cookie jar of
// its own to do this automatically. Public (02-add-fleet-auth, WU3): reused
// from dev.fleetpulse.api.mqtt.credentials' CrossOrganizationMqttIsolationTest
// and ExpiredBrowserCredentialConnectionTest, which also need an
// authenticated dispatcher session.
public final class DispatcherLoginTestSupport {

    private DispatcherLoginTestSupport() {
    }

    public static ResponseEntity<String> login(TestRestTemplate restTemplate, String baseUrl, String email, String rawPassword) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("username", email);
        form.add("password", rawPassword);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        return restTemplate.postForEntity(baseUrl + "/login", new HttpEntity<>(form, headers), String.class);
    }

    // Task 4.1/4.2/4.3 discovery: since SecurityConfig's csrf().spa() started
    // also setting an XSRF-TOKEN cookie on the /login response (needed for
    // mutationHeadersFrom below), this can no longer assume the FIRST
    // Set-Cookie header is the session cookie -- Set-Cookie ordering across
    // two cookies set by different filters in the chain is not guaranteed.
    // Combining every cookie into one Cookie header side-steps that
    // ordering entirely and is harmless for GET requests (an extra
    // XSRF-TOKEN cookie is simply ignored by Spring Security's CSRF filter
    // for safe methods).
    public static HttpHeaders sessionHeadersFrom(ResponseEntity<?> loginResponse) {
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.COOKIE, combinedCookieHeader(setCookiesOf(loginResponse)));
        return headers;
    }

    // Tasks 4.1/4.2/4.3 (02-add-fleet-auth, WU4): every state-changing
    // endpoint keeps Spring Security's default CSRF protection --
    // SecurityConfig's csrf().spa() exempts only /login -- so a POST/DELETE
    // call needs BOTH the session cookie and the XSRF-TOKEN cookie echoed
    // back as the X-XSRF-TOKEN header. The /login response already carries
    // an XSRF-TOKEN Set-Cookie (CsrfFilter runs, and therefore writes the
    // cookie, on every request/response regardless of that path's own CSRF
    // exemption), so no extra bootstrap request is needed before this.
    public static HttpHeaders mutationHeadersFrom(ResponseEntity<?> loginResponse) {
        List<String> cookies = setCookiesOf(loginResponse);
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.COOKIE, combinedCookieHeader(cookies));
        headers.add("X-XSRF-TOKEN", csrfTokenFrom(cookies));
        return headers;
    }

    private static List<String> setCookiesOf(ResponseEntity<?> response) {
        List<String> cookies = response.getHeaders().get(HttpHeaders.SET_COOKIE);
        assertThat(cookies).isNotNull().isNotEmpty();
        return cookies;
    }

    private static String combinedCookieHeader(List<String> setCookieHeaders) {
        StringBuilder combined = new StringBuilder();
        for (String setCookie : setCookieHeaders) {
            if (!combined.isEmpty()) {
                combined.append("; ");
            }
            combined.append(setCookie.split(";", 2)[0]);
        }
        return combined.toString();
    }

    private static String csrfTokenFrom(List<String> setCookieHeaders) {
        for (String setCookie : setCookieHeaders) {
            String cookiePair = setCookie.split(";", 2)[0];
            if (cookiePair.startsWith("XSRF-TOKEN=")) {
                return cookiePair.substring("XSRF-TOKEN=".length());
            }
        }
        throw new AssertionError("the login response must set an XSRF-TOKEN cookie");
    }
}
