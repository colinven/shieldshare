package net.shieldshare.shieldshare.config;

import net.shieldshare.shieldshare.filter.payloadsize.RequestSizeFilter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

@Configuration
public class FilterConfig {

    @Bean
    public FilterRegistrationBean<RequestSizeFilter> requestSizeFilter(AppProperties config) {
        var registration = new FilterRegistrationBean<>(new RequestSizeFilter(config));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        registration.addUrlPatterns("/secrets/create");
        return registration;
    }
}
