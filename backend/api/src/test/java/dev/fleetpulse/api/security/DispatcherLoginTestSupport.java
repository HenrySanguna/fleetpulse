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
// its own to do this automatically.
final class DispatcherLoginTestSupport {

    private DispatcherLoginTestSupport() {
    }

    static ResponseEntity<String> login(TestRestTemplate restTemplate, String baseUrl, String email, String rawPassword) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("username", email);
        form.add("password", rawPassword);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        return restTemplate.postForEntity(baseUrl + "/login", new HttpEntity<>(form, headers), String.class);
    }

    static HttpHeaders sessionHeadersFrom(ResponseEntity<?> loginResponse) {
        List<String> cookies = loginResponse.getHeaders().get(HttpHeaders.SET_COOKIE);
        assertThat(cookies).isNotNull().isNotEmpty();
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.COOKIE, cookies.get(0).split(";", 2)[0]);
        return headers;
    }
}
