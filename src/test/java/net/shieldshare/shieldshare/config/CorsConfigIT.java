package net.shieldshare.shieldshare.config;

import net.shieldshare.shieldshare.support.AbstractPostgresIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;

/**
 * The frontend is a separate origin, so CORS is what stands between it and every call it makes. These run
 * against the real application context because the behavior under test is the browser contract produced by
 * {@link WebConfig#addCorsMappings}, not a method return value.
 * <p>
 * MockMvc does not apply {@code server.servlet.context-path}, so paths here have no {@code /api/v1} prefix.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "app.cors.allowed-origins=https://app.shieldshare.example")
class CorsConfigIT extends AbstractPostgresIT {

    private static final String ALLOWED_ORIGIN = "https://app.shieldshare.example";

    @Autowired
    private MockMvc mockMvc;

    @Test
    void answersThePreflightForASecretCreateFromAnAllowedOrigin() throws Exception {
        MvcResult result = mockMvc.perform(options("/secrets/create")
                        .header("Origin", ALLOWED_ORIGIN)
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "Content-Type"))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(result.getResponse().getHeader("Access-Control-Allow-Origin")).isEqualTo(ALLOWED_ORIGIN);
        assertThat(result.getResponse().getHeader("Access-Control-Allow-Methods")).contains("POST");
        assertThat(result.getResponse().getHeader("Access-Control-Allow-Headers")).contains("Content-Type");
    }

    /*
     * The preflight is cached so the browser stops sending an OPTIONS ahead of every fetch. Worth pinning:
     * without it, each create doubles into two round trips.
     */
    @Test
    void letsTheBrowserCacheThePreflightForAnHour() throws Exception {
        MvcResult result = mockMvc.perform(options("/secrets/create")
                        .header("Origin", ALLOWED_ORIGIN)
                        .header("Access-Control-Request-Method", "POST"))
                .andReturn();

        assertThat(result.getResponse().getHeader("Access-Control-Max-Age")).isEqualTo("3600");
    }

    @Test
    void rejectsThePreflightFromAnUnlistedOrigin() throws Exception {
        MvcResult result = mockMvc.perform(options("/secrets/create")
                        .header("Origin", "https://evil.example")
                        .header("Access-Control-Request-Method", "POST"))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(403);
        assertThat(result.getResponse().getHeader("Access-Control-Allow-Origin")).isNull();
    }

    /*
     * A GET is not preflighted, so the only thing keeping the response away from a foreign page is the
     * Allow-Origin header on the response itself.
     */
    @Test
    void tagsAConfigResponseWithTheAllowedOrigin() throws Exception {
        MvcResult result = mockMvc.perform(get("/config").header("Origin", ALLOWED_ORIGIN)).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(result.getResponse().getHeader("Access-Control-Allow-Origin")).isEqualTo(ALLOWED_ORIGIN);
    }

    @Test
    void leavesAConfigResponseUntaggedForAnUnlistedOrigin() throws Exception {
        MvcResult result = mockMvc.perform(get("/config").header("Origin", "https://evil.example")).andReturn();

        assertThat(result.getResponse().getHeader("Access-Control-Allow-Origin")).isNull();
    }

    /*
     * Credentials stay off: the API has no cookies or auth headers, and switching this on would let a
     * foreign page make calls carrying whatever the browser attaches on its own.
     */
    @Test
    void neverAllowsCredentialedCrossOriginRequests() throws Exception {
        MvcResult result = mockMvc.perform(get("/config").header("Origin", ALLOWED_ORIGIN)).andReturn();

        assertThat(result.getResponse().getHeader("Access-Control-Allow-Credentials")).isNull();
    }
}
