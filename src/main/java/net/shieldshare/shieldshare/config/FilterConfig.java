package net.shieldshare.shieldshare.config;

import net.shieldshare.shieldshare.filter.payloadsize.RequestSizeFilter;
import net.shieldshare.shieldshare.filter.security.SecurityHeadersFilter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

@Configuration
public class FilterConfig {

    /**
     * Ordered ahead of {@link RequestSizeFilter} so that a rejected oversized request still carries the
     * headers. Mapped to the secret routes only - {@code /config} sets its own {@code Cache-Control} and
     * would be clobbered by the {@code no-store} here.
     */
    @Bean
    public FilterRegistrationBean<SecurityHeadersFilter> securityHeadersFilter() {
        var registration = new FilterRegistrationBean<>(new SecurityHeadersFilter());
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 5);
        registration.addUrlPatterns("/secrets/*");
        return registration;
    }

    @Bean
    public FilterRegistrationBean<RequestSizeFilter> requestSizeFilter(AppProperties config) {
        var registration = new FilterRegistrationBean<>(new RequestSizeFilter(config));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        registration.addUrlPatterns("/secrets/create");
        return registration;
    }
}
