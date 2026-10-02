package com.tovarika.tech.images.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

class OpenAiResponsesClientTest {
    @TempDir
    Path tempDir;

    @Test
    void sendsImagePromptAndSchemaAndParsesStructuredResult() throws IOException {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        OpenAiResponsesClient client = client(builder);
        server.expect(requestTo("https://api.openai.test/v1/responses"))
                .andExpect(header("Authorization", "Bearer test-key"))
                .andExpect(header("X-Client-Request-Id", org.hamcrest.Matchers.startsWith("job_123-")))
                .andExpect(jsonPath("$.model").value("vision-test"))
                .andExpect(jsonPath("$.store").value(false))
                .andExpect(jsonPath("$.instructions").value("Test product analysis prompt"))
                .andExpect(jsonPath("$.input[0].content[1].type").value("input_image"))
                .andExpect(jsonPath("$.input[0].content[1].image_url").value("data:image/png;base64,AQID"))
                .andExpect(jsonPath("$.input[0].content[1].detail").value("high"))
                .andExpect(jsonPath("$.text.format.type").value("json_schema"))
                .andExpect(jsonPath("$.text.format.strict").value(true))
                .andExpect(jsonPath("$.text.format.schema.additionalProperties").value(false))
                .andRespond(withSuccess("""
                        {
                          "id": "resp_test",
                          "status": "completed",
                          "output": [{
                            "type": "message",
                            "content": [{
                              "type": "output_text",
                              "text": "{\\\"title\\\":\\\"Кроссовки\\\",\\\"description\\\":\\\"Белые кроссовки.\\\",\\\"idea\\\":\\\"Светлая карточка с акцентом на подошву.\\\"}"
                            }]
                          }]
                        }
                        """, MediaType.APPLICATION_JSON));

        var result = client.analyze("vision-test", "job_123", new byte[] {1, 2, 3}, "image/png");

        assertThat(result.title()).isEqualTo("Кроссовки");
        assertThat(result.description()).isEqualTo("Белые кроссовки.");
        assertThat(result.idea()).isEqualTo("Светлая карточка с акцентом на подошву.");
        server.verify();
    }

    @Test
    void rejectsRefusalWithoutExposingProviderContent() throws IOException {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        OpenAiResponsesClient client = client(builder);
        server.expect(requestTo("https://api.openai.test/v1/responses"))
                .andRespond(withSuccess("""
                        {
                          "status": "completed",
                          "output": [{
                            "type": "message",
                            "content": [{"type": "refusal", "refusal": "private provider explanation"}]
                          }]
                        }
                        """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.analyze("vision-test", "job_456", new byte[] {1}, "image/png"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("OpenAI analysis request failed")
                .hasMessageNotContaining("private provider explanation");
        server.verify();
    }

    @Test
    void liveModeRequiresCredentialsAndModel() {
        var properties = new OpenAiProperties("live", "https://api.openai.com/v1", "", "", "");

        assertThatThrownBy(() -> new OpenAiResponsesClient(properties,
                new ProductAnalysisPrompt("prompt"), new ObjectMapper(), RestClient.builder()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("OPENAI_API_KEY_FILE");
    }

    private OpenAiResponsesClient client(RestClient.Builder builder) throws IOException {
        Path apiKeyFile = tempDir.resolve("openai_api_key");
        Files.writeString(apiKeyFile, "test-key\n");
        var properties = new OpenAiProperties(
                "live", "https://api.openai.test/v1", apiKeyFile.toString(), "", "vision-test");
        return new OpenAiResponsesClient(properties,
                new ProductAnalysisPrompt("Test product analysis prompt"), new ObjectMapper(), builder);
    }
}
