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
import java.util.Base64;
import java.util.List;
import com.tovarika.tech.images.application.GenerationRequest;
import com.tovarika.tech.templates.TemplatePlaceholder;
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
    void sendsTwoReferencesThroughImageToolAndNormalizesRequestedRatio() throws IOException {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        OpenAiResponsesClient client = client(builder);
        String result = Base64.getEncoder().encodeToString(TemplatePlaceholder.png());
        server.expect(requestTo("https://api.openai.test/v1/responses"))
                .andExpect(jsonPath("$.model").value("vision-test"))
                .andExpect(jsonPath("$.input[0].content[0].type").value("input_text"))
                .andExpect(jsonPath("$.input[0].content[1].type").value("input_image"))
                .andExpect(jsonPath("$.input[0].content[2].type").value("input_image"))
                .andExpect(jsonPath("$.tools[0].type").value("image_generation"))
                .andExpect(jsonPath("$.tools[0].model").value("image-test"))
                .andExpect(jsonPath("$.tools[0].action").value("edit"))
                .andExpect(jsonPath("$.tools[0].size").value("1024x1536"))
                .andRespond(withSuccess("""
                        {"status":"completed","output":[{"type":"image_generation_call","result":"%s"}]}
                        """.formatted(result), MediaType.APPLICATION_JSON));
        byte[] reference = TemplatePlaceholder.png();
        var generated = client.generate("vision-test", "image-test", new GenerationRequest(
                "Create a card", List.of(
                        new GenerationRequest.InputImage(reference, "image/png", "product"),
                        new GenerationRequest.InputImage(reference, "image/png", "template_reference")),
                1024, 1280));

        assertThat(generated.width()).isEqualTo(1024);
        assertThat(generated.height()).isEqualTo(1280);
        assertThat(generated.bytes()).isNotEmpty();
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
                "live", "https://api.openai.test/v1", apiKeyFile.toString(), "image-test", "vision-test");
        return new OpenAiResponsesClient(properties,
                new ProductAnalysisPrompt("Test product analysis prompt"), new ObjectMapper(), builder);
    }
}
