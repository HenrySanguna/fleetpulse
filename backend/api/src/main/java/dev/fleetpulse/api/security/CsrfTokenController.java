package dev.fleetpulse.api.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

// cross-site-csrf-token: not in SecurityConfig's permitAll list, so it falls
// under .anyRequest().authenticated() -- deliberately, not by omission. The
// only place the SPA needs a token is right before its first POST/PUT/PATCH/
// DELETE, which never happens before /login succeeds (the one CSRF-exempt
// endpoint), so there is no legitimate pre-auth caller to support. Requiring
// authentication also closes off the one way a stale token could leak in:
// Spring Security rotates the session's CSRF token on successful
// authentication, so a token handed out anonymously would already be wrong
// after the next login on that session -- authenticated-only means every
// token this endpoint ever returns was minted after that rotation already
// happened.
@RestController
public class CsrfTokenController {

    // Read off the request attribute CsrfFilter already populated, rather
    // than a `CsrfToken token` method parameter resolved via
    // CsrfTokenArgumentResolver: functionally identical (same attribute,
    // same value), but a `CsrfToken` parameter makes springdoc document it
    // as a request input and leak Spring Security's own CsrfToken interface
    // into the published OpenAPI contract (and therefore libs/api-client) as
    // a spurious, unused model.
    @GetMapping("/api/csrf")
    public CsrfTokenResponse csrf(HttpServletRequest request) {
        CsrfToken token = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
        return CsrfTokenResponse.from(token);
    }
}
