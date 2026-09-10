package net.shieldshare.shieldshare.filter.security;

import net.shieldshare.shieldshare.config.AppProperties;
import net.shieldshare.shieldshare.controller.ConfigController;
import net.shieldshare.shieldshare.controller.SecretsController;
import net.shieldshare.shieldshare.dto.request.CreateSecretRequest;
import net.shieldshare.shieldshare.dto.response.CreateSecretResponse;
import net.shieldshare.shieldshare.dto.response.SecretValidationResponse;
import net.shieldshare.shieldshare.exception.GlobalExceptionHandler;
import net.shieldshare.shieldshare.exception.InvalidSecretException;
import net.shieldshare.shieldshare.filter.payloadsize.RequestSizeFilter;
import net.shieldshare.shieldshare.service.SecretsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * HTTP-level tests for {@link SecurityHeadersFilter}. Standalone MockMvc with the filter mapped the same way
 * {@link net.shieldshare.shieldshare.config.FilterConfig} maps it, so the exclusion of {@code /config} is
 * part of what is under test rather than an assumption.
 */
class SecurityHeadersFilterTest {

    private static final String BASE_64 = "YW55IGNhcm5hbCBwbGVhc3VyZS4=";
    private static final String SECRETS_PATTERN = "/secrets/*";

    private final SecretsService secretsService = Mockito.mock(SecretsService.class);
    private final ObjectMapper objectMapper = new JsonMapper();
    private final AppProperties props = new AppProperties(
            new AppProperties.SizeCaps(1_048_576L, 1_573_000L, 1_052_701L),
            List.of(300, 3600, 86400, 604800),
            null, null, null);

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new SecretsController(secretsService), new ConfigController(props))
                .setControllerAdvice(new GlobalExceptionHandler())
                .addFilter(new SecurityHeadersFilter(), SECRETS_PATTERN)
                .build();
    }

    private static void assertAllHeadersPresent(MockHttpServletResponse response) {
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(response.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(response.getHeader("X-Robots-Tag")).isEqualTo("noindex, nofollow, noarchive");
    }

    @Test
    void stampsEveryHeaderOnASuccessfulCreate() throws Exception {
        Mockito.when(secretsService.createSecret(any(), anyString()))
                .thenReturn(new CreateSecretResponse("Zx3kQ1p9aB2cD4eF5gH6iJ", Instant.now()));
        byte[] body = objectMapper.writeValueAsBytes(new CreateSecretRequest(BASE_64, 300));

        MockHttpServletResponse response = mockMvc.perform(post("/secrets/create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(201);
        assertAllHeadersPresent(response);
    }

    /*
     * The error paths matter more than the happy path here. A 404 body is written by the controller advice,
     * well after the filter has run, and it is the response a probing client sees most often.
     */
    @Test
    void stampsEveryHeaderOnAFailedFetch() throws Exception {
        Mockito.when(secretsService.fetchSecret(anyString(), anyString()))
                .thenThrow(new InvalidSecretException("Secret with ID abc does not exist"));

        MockHttpServletResponse response = mockMvc.perform(post("/secrets/fetch/abc")).andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(404);
        assertAllHeadersPresent(response);
    }

    @Test
    void stampsEveryHeaderOnAValidateThatFoundNothing() throws Exception {
        Mockito.when(secretsService.validateSecret(anyString(), anyString()))
                .thenReturn(new SecretValidationResponse(false));

        MockHttpServletResponse response = mockMvc.perform(get("/secrets/validate/abc")).andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(200);
        assertAllHeadersPresent(response);
    }

    /*
     * RequestSizeFilter aborts the request while Jackson is still reading the body. The headers survive that
     * only because this filter is ordered ahead of it and writes before the chain runs - which is the whole
     * reason FilterConfig pins the two orders relative to each other.
     */
    @Test
    void stampsEveryHeaderOnARequestRejectedAsOversized() throws Exception {
        byte[] body = objectMapper.writeValueAsBytes(new CreateSecretRequest(BASE_64, 300));
        long tooSmall = body.length - 1;

        MockMvc withSizeFilter = MockMvcBuilders
                .standaloneSetup(new SecretsController(secretsService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .addFilter(new SecurityHeadersFilter(), SECRETS_PATTERN)
                .addFilter(new RequestSizeFilter(new AppProperties(
                        new AppProperties.SizeCaps(tooSmall, tooSmall, tooSmall),
                        List.of(300), null, null, null)), "/secrets/create")
                .build();

        MockHttpServletResponse response = withSizeFilter.perform(post("/secrets/create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(413);
        assertAllHeadersPresent(response);
    }

    /*
     * Pins a decision rather than a behavior. CSP and Referrer-Policy govern documents, and nothing here is
     * one - a JSON body executes nothing and navigates nowhere, so both are inert on these responses and
     * belong on the frontend host instead. This fails if someone adds them back as free wins.
     */
    @Test
    void doesNotSetTheDocumentHeadersThatBelongToTheFrontendHost() throws Exception {
        Mockito.when(secretsService.validateSecret(anyString(), anyString()))
                .thenReturn(new SecretValidationResponse(false));

        MockHttpServletResponse response = mockMvc.perform(get("/secrets/validate/abc")).andReturn().getResponse();

        assertThat(response.getHeader("Content-Security-Policy")).isNull();
        assertThat(response.getHeader("Referrer-Policy")).isNull();
        assertThat(response.getHeader("Strict-Transport-Security")).isNull();
    }

    /*
     * /config is deliberately cacheable for five minutes. A filter mapped to /** would silently overwrite
     * that with no-store, so the URL pattern is load bearing, not decoration.
     */
    @Test
    void leavesTheCacheableConfigResponseAlone() throws Exception {
        MockHttpServletResponse response = mockMvc.perform(get("/config")).andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getHeader("Cache-Control")).isEqualTo("max-age=300, public");
        assertThat(response.getHeader("X-Content-Type-Options")).isNull();
    }
}
