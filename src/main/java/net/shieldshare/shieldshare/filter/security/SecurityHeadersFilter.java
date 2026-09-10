package net.shieldshare.shieldshare.filter.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.NonNull;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Stamps the response headers that the browser, not this application, is responsible for enforcing.
 * <p>
 * Registered against the secret routes only - see {@link net.shieldshare.shieldshare.config.FilterConfig}.
 * {@code /config} is deliberately cacheable and must keep its own {@code Cache-Control}.
 * <p>
 * A header only acts on the response it is attached to. Everything here returns JSON to a {@code fetch()}
 * call, which never becomes a document, so the headers that govern documents belong on whatever host serves
 * the frontend rather than here:
 * <ul>
 *   <li>{@code Content-Security-Policy} - nothing in a JSON response executes. The only case a policy here
 *       would cover is a browser navigating straight to an API URL, and {@code nosniff} already stops that
 *       body from being rendered as HTML. The policy that protects the key is the one on the frontend's
 *       HTML, and its {@code connect-src} needs to name this API's origin.</li>
 *   <li>{@code Referrer-Policy} - sets the policy for a document, governing the requests that document goes
 *       on to make. A JSON body makes none and navigates nowhere, so there is no referrer to suppress. The
 *       leak worth preventing is {@code /s/<secretId>} escaping the reveal page, which is the frontend's
 *       header to set.</li>
 *   <li>{@code Strict-Transport-Security} - does nothing over plain HTTP, so it would be a no-op in dev and
 *       a duplicate of what the edge already sends in prod.</li>
 * </ul>
 * They are listed rather than merely absent so that the next pass over this file - or the next security
 * checklist - does not add them back as free wins. A header that implies a protection it does not provide
 * makes the posture harder to reason about, which costs more than the header saves.
 */
public class SecurityHeadersFilter extends OncePerRequestFilter {

    /**
     * The fetch response is ciphertext for a record that has just been burned. A copy of it sitting in a
     * disk cache or a proxy would outlive the secret, which is the one thing the burn is supposed to prevent.
     * Only this application can set it - no frontend host can do it on our behalf.
     */
    static final String CACHE_CONTROL = "no-store";

    /**
     * Covers the one case where a response here really is a document: someone pasting an API URL into the
     * address bar. Without it a browser may sniff a JSON body into something it will render.
     */
    static final String CONTENT_TYPE_OPTIONS = "nosniff";

    /**
     * {@code /secrets/validate/{id}} is a GET, so it is a crawlable URL if one ever leaks into a page or a
     * chat that a crawler reads. Fetch is a POST and cannot be crawled at all.
     */
    static final String ROBOTS_TAG = "noindex, nofollow, noarchive";

    /*
     * Headers are written before the chain runs. Once a response is committed - the rate limiter writing a
     * 429 body straight to the output stream, for instance - header writes are dropped silently, so setting
     * them afterwards would give a filter that looks correct and does nothing.
     */
    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {
        response.setHeader("Cache-Control", CACHE_CONTROL);
        response.setHeader("X-Content-Type-Options", CONTENT_TYPE_OPTIONS);
        response.setHeader("X-Robots-Tag", ROBOTS_TAG);
        filterChain.doFilter(request, response);
    }
}
