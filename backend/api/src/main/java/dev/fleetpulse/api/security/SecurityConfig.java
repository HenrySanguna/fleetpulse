package dev.fleetpulse.api.security;

import jakarta.servlet.http.HttpServletResponse;
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

// Task 2.1/2.2/2.4: form-based dispatcher session auth. Entry point and login
// handlers are overridden to return plain status codes instead of Spring
// Security's default browser redirects -- this is a JSON API backing an
// Angular console, not a server-rendered login page.
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    // Task 2.1: Argon2 preferred over BCrypt (tasks.md). Spring Security's
    // Argon2PasswordEncoder salts each hash independently, so two encodings
    // of the same raw password never match byte-for-byte.
    @Bean
    public PasswordEncoder passwordEncoder() {
        return Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();
    }

    @Bean
    public SecurityFilterChain dispatcherSecurityFilterChain(
            HttpSecurity http,
            DeactivatedDispatcherSessionFilter deactivatedDispatcherSessionFilter) throws Exception {
        http
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
            // to learn the expected token. csrf().spa() is Spring Security's
            // own built-in single-page-application recipe: it swaps in
            // CookieCsrfTokenRepository (a JS-readable, non-HttpOnly
            // `XSRF-TOKEN` cookie) and a request handler that resolves the
            // token eagerly on every response and accepts it back verbatim
            // via the `X-XSRF-TOKEN` header -- exactly the convention
            // Angular's HttpClient implements out of the box. /login stays
            // exempt because there is no prior response to have sourced that
            // cookie from yet.
            .csrf(csrf -> csrf.spa().ignoringRequestMatchers("/login"))
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
