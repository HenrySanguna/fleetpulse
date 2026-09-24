package dev.fleetpulse.api.security;

import org.springframework.security.web.csrf.CsrfToken;

// cross-site-csrf-token: deliberately narrower than Spring Security's own
// CsrfToken (which also exposes parameterName, a hidden-form-field concern
// this JSON API has no use for). The SPA only ever needs to know which
// header to set and what value to put in it.
public record CsrfTokenResponse(String headerName, String token) {

    public static CsrfTokenResponse from(CsrfToken token) {
        return new CsrfTokenResponse(token.getHeaderName(), token.getToken());
    }
}
