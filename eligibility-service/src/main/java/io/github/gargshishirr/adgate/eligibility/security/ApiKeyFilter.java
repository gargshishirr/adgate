package io.github.gargshishirr.adgate.eligibility.security;

import java.io.IOException;
import java.util.Set;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.gargshishirr.adgate.eligibility.security.ApiKeys.Role;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * API key check for the main HTTP port. Deny by default:
 * <ul>
 *   <li>The liveness and readiness probes ({@code /livez}, {@code /readyz}) are public: status only, no details.</li>
 *   <li>{@code /v1/eligibility} needs a client key.</li>
 *   <li>Everything else (campaign admin, explain and replay, anything unknown) needs the admin key.</li>
 * </ul>
 * The key is sent as {@code X-API-Key: <key>} or {@code Authorization: Bearer <key>}. Paths are matched exactly on
 * the raw request URI, and any URI with characters this API never uses (encoded characters, ';', '//', dot segments)
 * is refused first, so a request cannot be routed differently from how it was checked.
 *
 * <p>Actuator (metrics, Prometheus, circuit breakers) runs on a separate management server bound to 127.0.0.1, so
 * this filter does not run there. Spring Boot refuses to start if that port is set equal to the API port while the
 * management address is set, so actuator cannot silently move onto this port.
 */
public class ApiKeyFilter extends OncePerRequestFilter {

    public static final String API_KEY_HEADER = "X-API-Key";

    private static final Set<String> PUBLIC_PATHS = Set.of("/livez", "/readyz");
    private static final String CLIENT_PATH = "/v1/eligibility";
    private static final int MAX_PATH_LENGTH = 2048;
    private static final String BEARER = "Bearer ";

    private final ApiKeys keys;
    private final ObjectMapper mapper;

    public ApiKeyFilter(ApiKeys keys, ObjectMapper mapper) {
        this.keys = keys;
        this.mapper = mapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");

        String path = pathWithinApplication(request);
        if (path == null) {
            ProblemResponses.write(response, mapper, HttpStatus.BAD_REQUEST, "The request path is not valid.");
            return;
        }
        if (PUBLIC_PATHS.contains(path)) {
            chain.doFilter(request, response);
            return;
        }
        Role required = CLIENT_PATH.equals(path) ? Role.CLIENT : Role.ADMIN;
        String presented = presentedKey(request);
        if (presented == null) {
            unauthorized(response, "An API key is required. Send it in the " + API_KEY_HEADER + " header.");
            return;
        }
        Role role = keys.roleOf(presented);
        if (role == null) {
            unauthorized(response, "The API key is not valid.");
            return;
        }
        if (role != required) {
            ProblemResponses.write(response, mapper, HttpStatus.FORBIDDEN, required == Role.ADMIN
                    ? "This endpoint needs the admin key." : "This endpoint needs a client key.");
            return;
        }
        chain.doFilter(request, response);
    }

    /** The raw path without the context path, or null if it contains anything a valid AdGate path never does. */
    static String pathWithinApplication(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (uri == null || contextPath == null || !uri.startsWith(contextPath)) {
            return null;
        }
        String path = uri.substring(contextPath.length());
        return isCleanPath(path) ? path : null;
    }

    static boolean isCleanPath(String path) {
        if (path.isEmpty() || path.charAt(0) != '/' || path.length() > MAX_PATH_LENGTH) {
            return false;
        }
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            boolean allowed = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '/' || c == '.' || c == '_' || c == '-';
            if (!allowed) {
                return false;
            }
        }
        return !path.contains("//") && !path.contains("/./") && !path.contains("/../")
                && !path.endsWith("/.") && !path.endsWith("/..");
    }

    private static String presentedKey(HttpServletRequest request) {
        String apiKey = request.getHeader(API_KEY_HEADER);
        if (apiKey != null && !apiKey.isBlank()) {
            return apiKey.strip();
        }
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authorization != null && authorization.regionMatches(true, 0, BEARER, 0, BEARER.length())) {
            String token = authorization.substring(BEARER.length()).strip();
            return token.isEmpty() ? null : token;
        }
        return null;
    }

    private void unauthorized(HttpServletResponse response, String detail) throws IOException {
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer realm=\"adgate\"");
        ProblemResponses.write(response, mapper, HttpStatus.UNAUTHORIZED, detail);
    }
}
