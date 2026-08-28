package net.shieldshare.shieldshare.filter.payloadsize;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.NonNull;
import net.shieldshare.shieldshare.config.AppProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

public class RequestSizeFilter extends OncePerRequestFilter {

    private final AppProperties.SizeCaps config;

    @Autowired
    public RequestSizeFilter(AppProperties appProperties) {
        this(appProperties.sizeCaps());
    }
    RequestSizeFilter(AppProperties.SizeCaps config) {
        this.config = config;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {
        filterChain.doFilter(new SizeLimitedServletRequest(request, config.maxRequestBytes()), response);
    }
}
