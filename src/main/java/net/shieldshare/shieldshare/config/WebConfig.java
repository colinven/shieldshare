package net.shieldshare.shieldshare.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.shieldshare.shieldshare.ratelimit.RateLimitInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.time.Duration;
import java.util.List;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {

    private static final Duration PREFLIGHT_CACHE = Duration.ofHours(1);

    private final RateLimitInterceptor rateLimitInterceptor;
    private final AppProperties appProperties;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(rateLimitInterceptor);
        WebMvcConfigurer.super.addInterceptors(registry);
    }

    /**
     * The frontend is served from its own origin, so every call it makes is cross-origin and the browser
     * blocks it unless we name that origin here.
     * <p>
     * Credentials stay off. There are no cookies, sessions, or auth headers in this API - the link and the
     * key in its fragment are the only credentials, and neither travels as an ambient one. Leaving
     * credentials off means a malicious page cannot ride along on anything the browser attaches by itself.
     * <p>
     * An empty origin list registers nothing, so a deployment that forgot to configure origins fails closed
     * with a browser-side CORS error rather than open to the whole web.
     */
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        List<String> origins = appProperties.cors().allowedOrigins();
        if (origins.isEmpty()) {
            log.warn("app.cors.allowed-origins is empty. No cross-origin frontend will be able to call this API.");
            return;
        }
        log.info("CORS enabled for origins: {}", origins);
        registry.addMapping("/**")
                .allowedOrigins(origins.toArray(String[]::new))
                .allowedMethods("GET", "POST")
                .allowedHeaders("Content-Type")
                .allowCredentials(false)
                .maxAge(PREFLIGHT_CACHE.toSeconds());
    }
}
