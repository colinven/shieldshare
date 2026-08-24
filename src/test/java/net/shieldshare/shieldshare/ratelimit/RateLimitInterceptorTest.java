package net.shieldshare.shieldshare.ratelimit;

import net.shieldshare.shieldshare.config.AppProperties;
import net.shieldshare.shieldshare.controller.SecretsController;
import net.shieldshare.shieldshare.dto.request.CreateSecretRequest;
import net.shieldshare.shieldshare.dto.response.CreateSecretResponse;
import net.shieldshare.shieldshare.dto.response.SecretPayloadResponse;
import net.shieldshare.shieldshare.dto.response.SecretValidationResponse;
import net.shieldshare.shieldshare.exception.GlobalExceptionHandler;
import net.shieldshare.shieldshare.exception.InvalidSecretException;
import net.shieldshare.shieldshare.ratelimit.support.MutableTimeMeter;
import net.shieldshare.shieldshare.service.SecretsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;

public class RateLimitInterceptorTest {

    private static final int FETCH_CAPACITY = 20;
    private static final int VALIDATE_CAPACITY = 60;
    private static final int TIGHTENED_CAPACITY = 5;
    private static final int CREATE_CAPACITY = 20;
    private static final int BYTE_CAPACITY = 100;
    private static final int MISS_CAPACITY = 10;
    private static final int PROBING_CAPACITY = 5;
    private static final Duration REFILL_PERIOD = Duration.ofMinutes(1);
    private static final Duration COOLDOWN_PERIOD = Duration.ofMinutes(5);
    private static final long MAX_CACHE_SIZE = 100;
    private static final Duration CACHE_EAA = Duration.ofMinutes(10);
    private static final String BASE_64 = "YW55IGNhcm5hbCBwbGVhc3VyZS4=";
    private static final String IP = "203.0.113.7";
    private static final int TTL = 300;

    private final MutableTimeMeter timeMeter = new MutableTimeMeter();
    // Jackson 3, matching the JsonMapper bean Boot 4 auto-configures. Jackson 2's ObjectMapper
    // cannot serialize the LocalDateTime in ErrorResponse without the jsr310 module.
    private final ObjectMapper objectMapper = JsonMapper.builder().build();
    private SecretsService secretsService;
    private MockMvc mockMvc;
    private LookupCircuitBreaker breaker;

    private AppProperties.RateLimit config() {
        return new AppProperties.RateLimit(
                new AppProperties.CreateLimits(
                        new AppProperties.Limit(CREATE_CAPACITY, CREATE_CAPACITY / 2, REFILL_PERIOD),
                        new AppProperties.Limit(BYTE_CAPACITY, BYTE_CAPACITY / 2, REFILL_PERIOD)),
                new AppProperties.LookupLimits(
                        new AppProperties.Limit(FETCH_CAPACITY, FETCH_CAPACITY / 2, REFILL_PERIOD),
                        new AppProperties.Limit(TIGHTENED_CAPACITY, TIGHTENED_CAPACITY, REFILL_PERIOD)),
                new AppProperties.LookupLimits(
                        new AppProperties.Limit(VALIDATE_CAPACITY, VALIDATE_CAPACITY / 2, REFILL_PERIOD),
                        new AppProperties.Limit(TIGHTENED_CAPACITY, TIGHTENED_CAPACITY, REFILL_PERIOD)),
                new AppProperties.Breaker(MISS_CAPACITY, REFILL_PERIOD, COOLDOWN_PERIOD, PROBING_CAPACITY),
                new AppProperties.BucketCache(MAX_CACHE_SIZE, CACHE_EAA));
    }
    @BeforeEach
    public void setup() {
        AppProperties.RateLimit config = config();
        secretsService = Mockito.mock(SecretsService.class);
        breaker = new LookupCircuitBreaker(config.breaker(), timeMeter);

        RateLimitInterceptor interceptor = new RateLimitInterceptor(
                new BucketRegistry(config.cache(), timeMeter),
                new ClientIpResolver(),
                breaker,
                new RateLimitPolicy(config),
                objectMapper);

        mockMvc = MockMvcBuilders.standaloneSetup(new SecretsController(secretsService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .addInterceptors(interceptor)
                .build();
    }

    private void stubFetchHit() {
        Mockito.when(secretsService.fetchSecret(anyString(), any()))
                .thenReturn(new SecretPayloadResponse(BASE_64));
    }

    private void stubFetchMiss() {
        Mockito.when(secretsService.fetchSecret(anyString(), any()))
                .thenThrow(new InvalidSecretException("invalid secret"));
    }

    private void stubCreate() {
        Mockito.when(secretsService.createSecret(any(), anyString()))
                .thenReturn(new CreateSecretResponse("abc", Instant.now()));
    }

    private MvcResult fetch(String id, String ip) throws Exception {
        return mockMvc.perform(post("/secrets/fetch/" + id).with(request -> {
            request.setRemoteAddr(ip);
            return request;
        })).andReturn();
    }

    private MvcResult validate(String id, String ip) throws Exception {
        return mockMvc.perform(get("/secrets/validate/" + id).with(request -> {
            request.setRemoteAddr(ip);
            return request;
        })).andReturn();
    }

    private MvcResult create(String payload, int ttl, long contentLength) throws Exception {
        return mockMvc.perform(post("/secrets/create")
                        .header("content-length", contentLength)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateSecretRequest(payload, ttl)))
                .with(request -> {
                    request.setRemoteAddr(IP);
                    return request;
                })).andReturn();
    }

    @Test
    void allowsRequestsWithinByteBudget() throws Exception {
        stubCreate();
        assertThat(create(BASE_64, TTL, BYTE_CAPACITY).getResponse().getStatus()).isEqualTo(201);
    }

    @Test
    void rejectsRequestsAfterByteBudgetIsSpent() throws Exception {
        stubCreate();
        create(BASE_64, TTL, BYTE_CAPACITY);
        assertThat(create(BASE_64, TTL, BYTE_CAPACITY).getResponse().getStatus()).isEqualTo(429);
    }

    @Test
    void returns411WhenContentLengthIsMissing() throws Exception {
        var result = mockMvc.perform(post("/secrets/create")
                        .contentType(MediaType.APPLICATION_JSON)
                .with(request -> {
                    request.setRemoteAddr(IP);
                    return request;
                })).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(411);
    }

    @Test
    void refillsByteBudgetAsTimePasses() throws Exception {
        stubCreate();
        assertThat(create(BASE_64, TTL, BYTE_CAPACITY).getResponse().getStatus()).isEqualTo(201);
        assertThat(create(BASE_64, TTL, BYTE_CAPACITY).getResponse().getStatus()).isEqualTo(429);
        timeMeter.advance(REFILL_PERIOD);
        assertThat(create(BASE_64, TTL, BYTE_CAPACITY).getResponse().getStatus()).isEqualTo(201);
    }

    @Test
    void allowsRequestsWithinTheBudget() throws Exception {
        stubFetchHit();
        for (int i = 0; i < FETCH_CAPACITY; i++) {
            assertThat(fetch("abc", IP).getResponse().getStatus()).isEqualTo(200);
        }
    }


    @Test
    void rejectsTheRequestAfterTheBudgetIsSpent() throws Exception {
        stubFetchHit();
        for (int i = 0; i < FETCH_CAPACITY; i++) {
            fetch("abc", IP);
        }
        assertThat(fetch("abc", IP).getResponse().getStatus()).isEqualTo(429);
    }

    @Test
    void rejectedRequestsDoNotReachTheServiceClass() throws Exception {
        stubFetchHit();
        for (int i = 0; i < FETCH_CAPACITY; i++) {
            fetch("abc", IP);
        }
        Mockito.clearInvocations(secretsService);
        fetch("abc", IP);
        Mockito.verifyNoInteractions(secretsService);
    }

    @Test
    void sendsIdenticalResponseMessagesForHitAndMiss() throws Exception {
        stubFetchHit();
        for (int i = 0; i < FETCH_CAPACITY; i++) {
            fetch("abc", IP);
        }
        String hitResult = fetch("abc", IP).getResponse().getContentAsString();
        stubFetchMiss();
        String missResult = fetch("abc", IP).getResponse().getContentAsString();

        assertThat(objectMapper.readTree(hitResult).get("message"))
                .isEqualTo(objectMapper.readTree(missResult).get("message"));
        assertThat(objectMapper.readTree(hitResult).get("status"))
                .isEqualTo(objectMapper.readTree(missResult).get("status"));
    }

    @Test
    void rejectsFetchAndValidateWithTheSameMessage() throws Exception {
        stubFetchHit();
        Mockito.when(secretsService.validateSecret(anyString(), anyString()))
                .thenReturn(new SecretValidationResponse(true));
        for (int i = 0; i < FETCH_CAPACITY; i++) {
            fetch("abc", IP);
        }
        String fetchResult = fetch("abc", IP).getResponse().getContentAsString();
        for (int i = 0; i < VALIDATE_CAPACITY; i++) {
            validate("abc", IP);
        }
        String validateResult = validate("abc", IP).getResponse().getContentAsString();

        assertThat(objectMapper.readTree(fetchResult).get("message"))
                .isEqualTo(objectMapper.readTree(validateResult).get("message"));
    }

    @Test
    void keepsSeparateClientsIndependent() throws Exception {
        stubFetchHit();
        for (int i = 0; i < FETCH_CAPACITY; i++) {
            fetch("abc", "203.0.113.7");
        }

        assertThat(fetch("abc", "203.0.113.8").getResponse().getStatus()).isEqualTo(200);
    }

    /*
     * Two addresses inside one /64 are the same host as far as the limiter is concerned.
     */
    @Test
    void sharesOneBudgetAcrossAnIpv6SlashSixtyFour() throws Exception {
        stubFetchHit();
        for (int i = 0; i < FETCH_CAPACITY; i++) {
            fetch("abc", "2001:db8:1234:5678::1");
        }

        assertThat(fetch("abc", "2001:db8:1234:5678:ffff::9").getResponse().getStatus())
                .isEqualTo(429);
    }

    @Test
    void refillsTheBudgetAsTimePasses() throws Exception {
        stubFetchHit();
        for (int i = 0; i < FETCH_CAPACITY; i++) {
            fetch("abc", "203.0.113.7");
        }
        assertThat(fetch("abc", "203.0.113.7").getResponse().getStatus()).isEqualTo(429);

        timeMeter.advance(Duration.ofMinutes(1));

        assertThat(fetch("abc", "203.0.113.7").getResponse().getStatus()).isEqualTo(200);
    }

    /*
     * The miss signal. A 404 from a lookup route is exactly what the service already decided, so the
     * interceptor reads it off the response rather than making SecretsService aware the breaker exists.
     */
    @Test
    void feedsLookupMissesToTheCircuitBreaker() throws Exception {
        stubFetchMiss();

        for (int i = 0; i < FETCH_CAPACITY; i++) {
            fetch("bogus-" + i, "203.0.113." + i);
        }

        assertThat(breaker.state()).isEqualTo(net.shieldshare.shieldshare.ratelimit.BreakerState.OPEN);
    }

    @Test
    void doesNotFeedSuccessfulLookupsToTheCircuitBreaker() throws Exception {
        stubFetchHit();

        for (int i = 0; i < FETCH_CAPACITY; i++) {
            fetch("abc", "203.0.113.7");
        }

        assertThat(breaker.state()).isEqualTo(net.shieldshare.shieldshare.ratelimit.BreakerState.CLOSED);
    }

    /*
     * With the breaker open, fetch drops from 20 to 3. A client that has spent nothing yet gets the
     * tightened budget, not the normal one.
     */
    @Test
    void appliesTheTightenedBudgetWhileTheBreakerIsOpen() throws Exception {
        stubFetchMiss();
        for (int i = 0; i < FETCH_CAPACITY; i++) {
            fetch("bogus-" + i, "203.0.113." + i);
        }
        assertThat(breaker.state()).isEqualTo(net.shieldshare.shieldshare.ratelimit.BreakerState.OPEN);

        String freshClient = "198.51.100.4";
        for (int i = 0; i < TIGHTENED_CAPACITY; i++) {
            assertThat(fetch("bogus", freshClient).getResponse().getStatus()).isEqualTo(404);
        }

        assertThat(fetch("bogus", freshClient).getResponse().getStatus()).isEqualTo(429);
    }

    /*
     * Each route carries its own budget. Burning through fetch must not cost the same client its
     * ability to validate.
     */
    @Test
    void keepsEachRoutesBudgetSeparate() throws Exception {
        stubFetchHit();
        Mockito.when(secretsService.validateSecret(anyString(), any()))
                .thenReturn(new SecretValidationResponse(true));

        for (int i = 0; i < FETCH_CAPACITY; i++) {
            fetch("abc", "203.0.113.7");
        }
        assertThat(fetch("abc", "203.0.113.7").getResponse().getStatus()).isEqualTo(429);

        assertThat(validate("abc", "203.0.113.7").getResponse().getStatus()).isEqualTo(200);
    }
}
