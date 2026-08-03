package net.shieldshare.shieldshare.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Binding tests for {@link AppProperties}. No database and no full application context - the
 * ApplicationContextRunner does a real binding pass on its own.
 */
class AppPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
            .withUserConfiguration(EnableProps.class)
            .withPropertyValues(
                    "app.size-caps.max-content-bytes=1048576",
                    "app.size-caps.max-request-bytes=1573000",
                    "app.size-caps.max-blob-bytes=1052701",
                    "app.ttl-options-seconds=300,3600,86400,604800");

    @Test
    void bindsSizeCapsFromTheAppConfigTree() {
        runner.run(context -> {
            AppProperties props = context.getBean(AppProperties.class);
            assertThat(props.sizeCaps().maxContentBytes()).isEqualTo(1_048_576L);
            assertThat(props.sizeCaps().maxRequestBytes()).isEqualTo(1_573_000L);
            assertThat(props.sizeCaps().maxBlobBytes()).isEqualTo(1_052_701L);
        });
    }

    /*
     * app.ttl-options-seconds is a single comma-separated string in application.yaml. @Value used
     * to split it for us; this proves @ConfigurationProperties relaxed binding does the same.
     */
    @Test
    void bindsCommaSeparatedTtlStringIntoAListOfIntegers() {
        runner.run(context -> assertThat(context.getBean(AppProperties.class).ttlOptionsSeconds())
                .containsExactly(300, 3600, 86400, 604800));
    }

    @EnableConfigurationProperties(AppProperties.class)
    static class EnableProps {
    }
}
