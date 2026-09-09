package net.shieldshare.shieldshare.controller;

import net.shieldshare.shieldshare.config.AppProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * HTTP-level tests for {@link ConfigController}. Standalone MockMvc, so there is no Spring context
 * and no database - the controller only reads {@link AppProperties}, which is built by hand here.
 */
class ConfigControllerTest {

    private static final long MAX_CONTENT_BYTES = 1_048_576L;
    private static final List<Integer> TTL_OPTIONS = List.of(300, 3600, 86400, 604800);

    private MockMvc mockMvc;

    @BeforeEach
    void setup() {
        AppProperties props = new AppProperties(
                new AppProperties.SizeCaps(MAX_CONTENT_BYTES, 1_573_000L, 1_052_701L),
                TTL_OPTIONS,
                null,
                null,
                null);

        mockMvc = MockMvcBuilders.standaloneSetup(new ConfigController(props)).build();
    }

    @Test
    void returnsTheClientFacingConfigValues() throws Exception {
        MvcResult result = mockMvc.perform(get("/config")).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(result.getResponse().getContentAsString())
                .contains("\"maxContentBytes\":" + MAX_CONTENT_BYTES);
    }

    /*
     * app.ttl-options-seconds is a comma-separated string in application.yaml. The client needs a
     * real JSON array of numbers in config order, not that string echoed back.
     */
    @Test
    void serializesTtlOptionsAsAJsonArrayOfNumbersInConfigOrder() throws Exception {
        MvcResult result = mockMvc.perform(get("/config")).andReturn();

        assertThat(result.getResponse().getContentAsString())
                .contains("\"ttlOptionsSeconds\":[300,3600,86400,604800]");
    }

    /*
     * These values only change on redeploy, so the browser and any edge proxy are free to hold the
     * response for a short window instead of hitting the app on every page load.
     */
    @Test
    void allowsTheResponseToBeCachedPubliclyForFiveMinutes() throws Exception {
        MvcResult result = mockMvc.perform(get("/config")).andReturn();

        assertThat(result.getResponse().getHeader("Cache-Control"))
                .isEqualTo("max-age=300, public");
    }
}
