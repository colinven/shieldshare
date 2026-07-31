package net.shieldshare.shieldshare.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.security.SecureRandom;

@Configuration
public class ApplicationConfig {

    @Bean
    public SecureRandom secureRandom() {
        return new SecureRandom();
    }
}
