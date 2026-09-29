package dev.fleetpulse.api.security;

import dev.fleetpulse.api.config.FleetpulseCorsProperties;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

// Task 2.1/2.2/2.4: form-based dispatcher session auth. Entry point and login
// handlers are overridden to return plain status codes instead of Spring
// Security's default browser redirects -- this is a JSON API backing an
// Angular console, not a server-rendered login page.
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@EnableConfigurationProperties(FleetpulseCorsProperties.class)
public class SecurityConfig {

    // Task 2.1: Argon2 preferred over BCrypt (tasks.md). Spring Security's
    // Argon2PasswordEncoder salts each hash independently, so two encodings
    // of the same raw password never match byte-for-byte.
    @Bean
    public PasswordEncoder passwordEncoder() {
        return Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();
    }

    // Previously missing entirely: with no CorsConfigurationSource bean,
    // Spring Security adds no CORS response headers at all, so a real
    // browser silently blocks the console (a different origin in every
    // environment this project deploys to) from reading any api response,
    // credentialed or not -- this was never exercised before a real browser
    // actually tried to log in. `allowCredentials(true)` requires listing
    // explicit origin patterns, never "*" (Spring rejects that combination
    // outright), hence FleetpulseCorsProperties instead of a wildcard.
    @Bean
    public CorsConfigurationSource corsConfigurationSource(FleetpulseCorsProperties properties) {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOriginPatterns(properties.allowedOriginPatterns());
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        // "X-CSRF-TOKEN": HttpSessionCsrfTokenRepository's default header
        // name (cross-site-csrf-token) -- CsrfTokenController hands this
        // name back dynamically, but the browser's own CORS preflight still
        // needs it allow-listed here independently, or it would block the
        // console from ever sending it cross-site.
        configuration.setAllowedHeaders(List.of("Content-Type", "X-CSRF-TOKEN"));
        configuration.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    @Bean
    public SecurityFilterChain dispatcherSecurityFilterChain(
            HttpSecurity http,
            DeactivatedDispatcherSessionFilter deactivatedDispatcherSessionFilter,
            CorsConfigurationSource corsConfigurationSource) throws Exception {
        http
            .cors(cors -> cors.configurationSource(corsConfigurationSource))
            .authorizeHttpRequests(authorize -> authorize
                .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                .requestMatchers("/login").permitAll()
                // Published for libs/api-client codegen (00-bootstrap-monorepo,
                // section 4; OpenApiDocumentPublicationTest, pre-existing
                // before this change) -- the document describes the API, it
                // is not itself sensitive.
                .requestMatchers("/v3/api-docs/**").permitAll()
                // Same reasoning, same non-sensitivity, as /v3/api-docs/**
                // above: springdoc-openapi-starter-webmvc-ui (already on the
                // classpath since 00-bootstrap-monorepo) serves this
                // interactive documentation page from that same document.
                // Without this it would have been a latent permission gap
                // introduced by adding Spring Security in WU2, caught and
                // closed here (02-add-fleet-auth, WU3).
                .requestMatchers("/swagger-ui.html", "/swagger-ui/**").permitAll()
                .anyRequest().authenticated())
            .formLogin(form -> form
                .loginProcessingUrl("/login")
                .successHandler((request, response, authentication) -> response.setStatus(HttpServletResponse.SC_OK))
                .failureHandler((request, response, exception) -> response.sendError(HttpServletResponse.SC_UNAUTHORIZED))
                .permitAll())
            // F8: same JSON-API reasoning as formLogin above, missed when
            // formLogin was fixed -- with no .logout(...) configured here,
            // Spring Security's default SimpleUrlLogoutSuccessHandler
            // answers POST /logout with a 302 to /login?logout, built from
            // request.getScheme(). Caddy already forwards
            // X-Forwarded-Proto: https, but Spring ignores it without
            // server.forward-headers-strategy (application-prod.yml), so
            // production issued an http:// Location over an https://
            // connection -- blocked by the browser as mixed content.
            // Answering with SC_OK (not 204) matches formLogin's own
            // successHandler above and reuses the exact response shape the
            // console's postForm() (api-client-json-get.ts) already reads
            // with responseType: 'text' and discards -- a path already
            // proven against a real browser for /login, so /logout needs
            // no new client-side behavior to trust.
            // invalidateHttpSession/clearAuthentication stay at their
            // defaults (true); no explicit deleteCookies(...) is added
            // since there is no separate remember-me cookie here -- Spring
            // Session's own cookie serializer expires the SESSION cookie,
            // with the same HttpOnly/Secure/SameSite=None attributes
            // application.yml configures for login's cookie, once the
            // session it tracks is invalidated.
            .logout(logout -> logout
                .logoutUrl("/logout")
                .logoutSuccessHandler((request, response, authentication) -> response.setStatus(HttpServletResponse.SC_OK)))
            .exceptionHandling(exceptions -> exceptions
                .authenticationEntryPoint((request, response, authException) ->
                    response.sendError(HttpServletResponse.SC_UNAUTHORIZED)))
            // Task 4.1/4.2/4.3 discovery: this is a JSON API with no
            // server-rendered view, so the plain session-bound CSRF
            // protection (HttpSessionCsrfTokenRepository + a hidden-form
            // token) has no page to embed the token in -- every mutation
            // endpoint an authenticated dispatcher calls (device credential
            // provisioning/revocation/rotation being the first ones this
            // change actually exercises over HTTP) was silently rejected
            // with 403 before this fix, since the client never had any way
            // to learn the expected token.
            //
            // cross-site-csrf-token: originally used csrf().spa() (Spring
            // Security's cookie-based SPA recipe: CookieCsrfTokenRepository
            // + SpaCsrfTokenRequestHandler). That does not survive the
            // console (fleetpulse-console.pages.dev) and api (its own host)
            // being genuinely different sites -- two independent failures:
            // (1) the console's own document.cookie can never read a cookie
            // a *different* origin set, no matter its SameSite/HttpOnly
            // attributes, so the SPA has no way to mirror the value into a
            // header; (2) even if it could, CookieCsrfTokenRepository treats
            // the *incoming* cookie as the source of truth, and a cross-site
            // XHR/fetch is not guaranteed to echo it back.
            // HttpSessionCsrfTokenRepository sidesteps both: the expected
            // token lives server-side, keyed by the `SESSION` cookie
            // (SameSite=None, already reliably sent -- see application.yml),
            // and CsrfTokenController (GET /api/csrf) hands the token to the
            // SPA over the response body instead of a cookie.
            //
            // .spa() itself is also dropped, not just its repository: its
            // SpaCsrfTokenRequestHandler is built for that cookie recipe
            // specifically -- on the way back in, it treats a *header*
            // value as the raw, unmasked token verbatim (matching a
            // JS-read cookie), never XOR-unmasking it. But the token this
            // endpoint hands out is read via CsrfTokenArgumentResolver
            // (`CsrfToken token` controller parameter), which Spring
            // Security's plain default CsrfFilter requestHandler
            // (XorCsrfTokenRequestAttributeHandler -- BREACH protection,
            // wired in even without calling .spa()) always exposes
            // XOR-masked. Pairing that masked value with a handler that
            // expects raw-in-header, as .spa() does, can never validate --
            // confirmed live (every mutating request rejected 403 even with
            // a same-session token attached). Leaving the default handler
            // in place keeps generation (masked-out) and validation
            // (masked-in, XOR-unmasked before comparing) consistent.
            // Spring Security also rotates the session's CSRF token on
            // successful authentication (session-fixation protection), so a
            // token fetched before login is stale the instant /login
            // succeeds -- the SPA must call GET /api/csrf again after
            // login, never reuse a pre-login token (documented on the
            // frontend interceptor). /login stays exempt: there is no
            // authenticated session yet to have sourced a token from.
            .csrf(csrf -> csrf
                .csrfTokenRepository(new HttpSessionCsrfTokenRepository())
                .ignoringRequestMatchers("/login"))
            // Task 2.5: must run after SecurityContextHolderFilter (which
            // restores the Authentication persisted in the session) and
            // before AuthorizationFilter (which decides access), so a
            // deactivated dispatcher is already anonymous by the time
            // authorization runs.
            .addFilterAfter(deactivatedDispatcherSessionFilter, SecurityContextHolderFilter.class);
        return http.build();
    }

    // DeactivatedDispatcherSessionFilter is also a @Component (needed so
    // Spring can build it with its own dependencies before wiring it into
    // the chain above). Without this, Spring Boot would additionally
    // auto-register it as a second, independent servlet filter outside
    // Spring Security's own chain; OncePerRequestFilter would no-op that
    // second invocation, but registering it only once is the documented,
    // correct way to use a @Component filter exclusively through
    // HttpSecurity.
    @Bean
    public FilterRegistrationBean<DeactivatedDispatcherSessionFilter> disableAutoRegistrationOf(
            DeactivatedDispatcherSessionFilter deactivatedDispatcherSessionFilter) {
        FilterRegistrationBean<DeactivatedDispatcherSessionFilter> registration =
            new FilterRegistrationBean<>(deactivatedDispatcherSessionFilter);
        registration.setEnabled(false);
        return registration;
    }
}
