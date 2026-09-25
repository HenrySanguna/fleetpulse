package dev.fleetpulse.api.security;

import dev.fleetpulse.domain.Organization;
import dev.fleetpulse.domain.OrganizationRepository;
import dev.fleetpulse.domain.User;
import dev.fleetpulse.domain.UserRepository;
import dev.fleetpulse.domain.UserRole;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

// Tasks 2.1 (form login + Argon2 hash in practice), 2.2 (HttpOnly/Secure/
// SameSite session cookie -- None, not Strict, see application.yml's own
// deviation note), and 2.3 (per-request orgId resolution via
// GET /api/dispatchers/me).
@Testcontainers
@AutoConfigureTestRestTemplate
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class DispatcherSessionAuthenticationTest {

    private static final Path POSTGIS_PARTMAN_DOCKERFILE = Path
        .of(System.getProperty("user.dir"), "..", "..", "docker", "postgis-partman", "Dockerfile")
        .normalize();

    @Container
    static final PostgreSQLContainer postgis = new PostgreSQLContainer(
        DockerImageName
            .parse(
                new ImageFromDockerfile("fleetpulse/postgis-partman:test", false)
                    .withDockerfile(POSTGIS_PARTMAN_DOCKERFILE)
                    .get()
            )
            .asCompatibleSubstituteFor("postgres")
    );

    @DynamicPropertySource
    static void backingServices(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgis::getJdbcUrl);
        registry.add("spring.datasource.username", postgis::getUsername);
        registry.add("spring.datasource.password", postgis::getPassword);
        // No broker involved in this test; a placeholder satisfies
        // FleetpulseMqttProperties' @NotBlank binding (same pattern as
        // DispatcherSessionPersistenceTest from WU1).
        registry.add("fleetpulse.mqtt.broker-url", () -> "tcp://localhost:1");
    }

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private OrganizationRepository organizations;

    @Autowired
    private UserRepository users;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    void logsInWithValidCredentialsAndReceivesASecureSessionCookie() {
        seedDispatcher("acme-login", "ana@acme.test", "s3cret-pass", UserRole.DISPATCHER);

        ResponseEntity<String> response = DispatcherLoginTestSupport
            .login(restTemplate, baseUrl(), "ana@acme.test", "s3cret-pass");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<String> cookies = response.getHeaders().get(HttpHeaders.SET_COOKIE);
        assertThat(cookies).isNotNull().isNotEmpty();
        // Found by its own "SESSION=" prefix (Spring Session's default
        // cookie name), not assumed to be the only/first Set-Cookie header --
        // SecurityConfig's csrf().spa() no longer adds an XSRF-TOKEN cookie
        // (cross-site-csrf-token: CSRF is now HttpSessionCsrfTokenRepository-
        // backed, delivered via GET /api/csrf instead), but this stays
        // defensive rather than assuming exactly one cookie is ever set.
        String setCookie = cookies.stream()
            .filter(cookie -> cookie.startsWith("SESSION="))
            .findFirst()
            .orElseThrow(() -> new AssertionError("no SESSION cookie in " + cookies));
        assertThat(setCookie).contains("HttpOnly");
        assertThat(setCookie).contains("Secure");
        // SameSite=None, not Strict: deviation documented in application.yml
        // -- the console (Cloudflare Pages) and api are different sites
        // under this deployment topology, and Strict makes the browser
        // withhold the cookie on every request the console itself makes.
        assertThat(setCookie).containsIgnoringCase("SameSite=None");
    }

    // F8: production logout over HTTPS redirected the browser to an http://
    // URL (Spring Security's default logout success handler is a redirect,
    // built from request.getScheme(), which Spring never sees as "https"
    // behind Caddy without server.forward-headers-strategy) and got blocked
    // as mixed content. SecurityConfig now answers with a plain 200 instead
    // of any redirect -- asserted here by the absence of a Location header
    // too, not just the status code, since a 200 that still carried a
    // stray redirect Location would pass a status-only check just as
    // wrongly as a 3xx would.
    @Test
    void answersLogoutWithAPlainStatusAndNoRedirectAndEndsTheSession() {
        seedDispatcher("acme-logout", "dana@acme.test", "s3cret-pass", UserRole.DISPATCHER);

        ResponseEntity<String> loginResponse = DispatcherLoginTestSupport
            .login(restTemplate, baseUrl(), "dana@acme.test", "s3cret-pass");
        HttpHeaders logoutHeaders = DispatcherLoginTestSupport
            .mutationHeadersFrom(restTemplate, baseUrl(), loginResponse);

        ResponseEntity<String> logoutResponse = restTemplate.exchange(
            baseUrl() + "/logout", HttpMethod.POST, new HttpEntity<>(logoutHeaders), String.class);

        assertThat(logoutResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(logoutResponse.getHeaders().getLocation()).isNull();

        // Confirms Spring Session's cookie serializer expires the SESSION
        // cookie on invalidate, with the same attributes login's own cookie
        // carries (SecurityConfig relies on this instead of an explicit
        // deleteCookies(...) call).
        List<String> logoutCookies = logoutResponse.getHeaders().get(HttpHeaders.SET_COOKIE);
        assertThat(logoutCookies).isNotNull().isNotEmpty();
        String expiredCookie = logoutCookies.stream()
            .filter(cookie -> cookie.startsWith("SESSION="))
            .findFirst()
            .orElseThrow(() -> new AssertionError("no SESSION cookie in " + logoutCookies));
        assertThat(expiredCookie).containsIgnoringCase("Max-Age=0");
        assertThat(expiredCookie).contains("HttpOnly");
        assertThat(expiredCookie).contains("Secure");
        assertThat(expiredCookie).containsIgnoringCase("SameSite=None");

        // The session the logout call authenticated with must no longer
        // work -- confirms invalidateHttpSession's default did fire, not
        // just that the response looked right.
        ResponseEntity<String> afterLogout = restTemplate.exchange(
            baseUrl() + "/api/dispatchers/me", HttpMethod.GET,
            new HttpEntity<>(DispatcherLoginTestSupport.sessionHeadersFrom(loginResponse)), String.class);
        assertThat(afterLogout.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void rejectsLoginWithAnIncorrectPassword() {
        seedDispatcher("acme-badpass", "bea@acme.test", "s3cret-pass", UserRole.DISPATCHER);

        ResponseEntity<String> response = DispatcherLoginTestSupport
            .login(restTemplate, baseUrl(), "bea@acme.test", "wrong-password");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void rejectsUnauthenticatedAccessToAProtectedEndpoint() {
        ResponseEntity<String> response = restTemplate
            .getForEntity(baseUrl() + "/api/dispatchers/me", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void resolvesTheAuthenticatedDispatcherOrganizationOnEveryRequest() {
        Organization org = seedDispatcher("acme-me", "cata@acme.test", "s3cret-pass", UserRole.DISPATCHER);

        ResponseEntity<String> loginResponse = DispatcherLoginTestSupport
            .login(restTemplate, baseUrl(), "cata@acme.test", "s3cret-pass");
        HttpHeaders sessionHeaders = DispatcherLoginTestSupport.sessionHeadersFrom(loginResponse);

        ResponseEntity<DispatcherSelfView> response = restTemplate.exchange(
            baseUrl() + "/api/dispatchers/me",
            HttpMethod.GET,
            new HttpEntity<>(sessionHeaders),
            DispatcherSelfView.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().organizationId()).isEqualTo(org.getId());
        assertThat(response.getBody().email()).isEqualTo("cata@acme.test");
        assertThat(response.getBody().role()).isEqualTo(UserRole.DISPATCHER);
    }

    private Organization seedDispatcher(String orgName, String email, String rawPassword, UserRole role) {
        Organization org = organizations.save(new Organization(orgName));
        users.save(new User(org, email, passwordEncoder.encode(rawPassword), role));
        return org;
    }

    private String baseUrl() {
        return "http://localhost:" + port;
    }
}
