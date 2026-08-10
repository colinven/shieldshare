package net.shieldshare.shieldshare.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Duration;

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
                    "app.ttl-options-seconds=300,3600,86400,604800",
                    "app.sweeper.pass-limit=2500",
                    "app.sweeper.audit-log-retention=90d",
                    "app.sweeper.access-attempt-retention=7d",
                    "app.rate-limit.create.requests.capacity=20",
                    "app.rate-limit.create.requests.refill-tokens=10",
                    "app.rate-limit.create.requests.refill-period=1m",
                    "app.rate-limit.create.bytes.capacity=4194304",
                    "app.rate-limit.create.bytes.refill-tokens=20971520",
                    "app.rate-limit.create.bytes.refill-period=1h",
                    "app.rate-limit.fetch.closed.capacity=20",
                    "app.rate-limit.fetch.closed.refill-tokens=10",
                    "app.rate-limit.fetch.closed.refill-period=1m",
                    "app.rate-limit.fetch.open.capacity=3",
                    "app.rate-limit.fetch.open.refill-tokens=3",
                    "app.rate-limit.fetch.open.refill-period=1m",
                    "app.rate-limit.validate.closed.capacity=60",
                    "app.rate-limit.validate.closed.refill-tokens=30",
                    "app.rate-limit.validate.closed.refill-period=1m",
                    "app.rate-limit.validate.open.capacity=5",
                    "app.rate-limit.validate.open.refill-tokens=5",
                    "app.rate-limit.validate.open.refill-period=1m",
                    "app.rate-limit.breaker.miss-capacity=300",
                    "app.rate-limit.breaker.miss-refill-period=1m",
                    "app.rate-limit.breaker.probing-capacity=30",
                    "app.rate-limit.breaker.cooldown=5m",
                    "app.rate-limit.cache.max-size=100000",
                    "app.rate-limit.cache.expire-after-access=10m");

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

    /*
     * The retention windows are written as "90d" and "7d" in application.yaml. That shorthand is
     * Boot's Duration converter doing the work, not plain type coercion, so it is worth proving it
     * lands as a Duration of the right length rather than, say, 90 milliseconds.
     */
    @Test
    void bindsSweeperRetentionShorthandIntoDurations() {
        runner.run(context -> {
            AppProperties.Sweeper sweeper = context.getBean(AppProperties.class).sweeper();
            assertThat(sweeper.passLimit()).isEqualTo(2500);
            assertThat(sweeper.auditLogRetention()).isEqualTo(Duration.ofDays(90));
            assertThat(sweeper.accessAttemptRetention()).isEqualTo(Duration.ofDays(7));
        });
    }

    /*
     * The rate limit tree is the deepest nesting in the config, and the numbers in it are the ones
     * an operator will actually reach for during an incident. Prove the whole shape binds, not just
     * the top level.
     */
    @Test
    void bindsTheRateLimitTree() {
        runner.run(context -> {
            AppProperties.RateLimit rateLimit = context.getBean(AppProperties.class).rateLimit();

            assertThat(rateLimit.create().requests().capacity()).isEqualTo(20L);
            assertThat(rateLimit.create().requests().refillTokens()).isEqualTo(10L);
            assertThat(rateLimit.create().requests().refillPeriod()).isEqualTo(Duration.ofMinutes(1));
            assertThat(rateLimit.create().bytes().capacity()).isEqualTo(4_194_304L);
            assertThat(rateLimit.create().bytes().refillPeriod()).isEqualTo(Duration.ofHours(1));

            assertThat(rateLimit.fetch().closed().capacity()).isEqualTo(20L);
            assertThat(rateLimit.fetch().open().capacity()).isEqualTo(3L);
            assertThat(rateLimit.validate().closed().capacity()).isEqualTo(60L);
            assertThat(rateLimit.validate().open().capacity()).isEqualTo(5L);

            assertThat(rateLimit.breaker().missCapacity()).isEqualTo(300L);
            assertThat(rateLimit.breaker().probingCapacity()).isEqualTo(30L);
            assertThat(rateLimit.breaker().cooldown()).isEqualTo(Duration.ofMinutes(5));

            assertThat(rateLimit.cache().maxSize()).isEqualTo(100_000L);
            assertThat(rateLimit.cache().expireAfterAccess()).isEqualTo(Duration.ofMinutes(10));
        });
    }

    @EnableConfigurationProperties(AppProperties.class)
    static class EnableProps {
    }
}
