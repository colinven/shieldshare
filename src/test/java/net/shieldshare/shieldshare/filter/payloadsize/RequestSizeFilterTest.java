package net.shieldshare.shieldshare.filter.payloadsize;

import net.shieldshare.shieldshare.config.AppProperties;
import net.shieldshare.shieldshare.controller.SecretsController;
import net.shieldshare.shieldshare.dto.request.CreateSecretRequest;
import net.shieldshare.shieldshare.exception.GlobalExceptionHandler;
import net.shieldshare.shieldshare.service.SecretsService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

public class RequestSizeFilterTest {

    private final SecretsService secretsService = Mockito.mock(SecretsService.class);
    private final ObjectMapper objectMapper = new JsonMapper();
    private final GlobalExceptionHandler globalExceptionHandler = new GlobalExceptionHandler();

    private static final String BASE_64 = "YW55IGNhcm5hbCBwbGVhc3VyZS4=";

    private MockMvc mockMvc;

    @Test
    void bodyOverLimitReturns413() throws Exception {
        CreateSecretRequest dto = new CreateSecretRequest(BASE_64, 300);
        byte[] body = objectMapper.writeValueAsBytes(dto);
        long n = body.length - 1;

        mockMvc = MockMvcBuilders.standaloneSetup(new SecretsController(secretsService))
                .setControllerAdvice(globalExceptionHandler)
                .addFilter(new RequestSizeFilter(new AppProperties.SizeCaps(n,n,n)))
                .build();

        mockMvc.perform(post("/secrets/create")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
                .andExpect(status().isContentTooLarge());
    }

    @Test
    void bodyAtLimitReturns201() throws Exception {
        CreateSecretRequest dto = new CreateSecretRequest(BASE_64, 300);
        byte[] body = objectMapper.writeValueAsBytes(dto);
        long n = body.length;

        mockMvc = MockMvcBuilders.standaloneSetup(new SecretsController(secretsService))
                .setControllerAdvice(globalExceptionHandler)
                .addFilter(new RequestSizeFilter(new AppProperties.SizeCaps(n,n,n)))
                .build();

        mockMvc.perform(post("/secrets/create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated());
    }

    @Test
    void bodyUnderLimitReturns201() throws Exception {
        CreateSecretRequest dto = new CreateSecretRequest(BASE_64, 300);
        byte[] body = objectMapper.writeValueAsBytes(dto);
        long n = body.length + 1;

        mockMvc = MockMvcBuilders.standaloneSetup(new SecretsController(secretsService))
                .setControllerAdvice(globalExceptionHandler)
                .addFilter(new RequestSizeFilter(new AppProperties.SizeCaps(n,n,n)))
                .build();

        mockMvc.perform(post("/secrets/create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated());
    }
}
