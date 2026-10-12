package com.tovarika.tech.images.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import com.tovarika.tech.images.application.GenerationRequest;
import com.tovarika.tech.templates.TemplatePlaceholder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import javax.imageio.ImageIO;
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
                .andExpect(jsonPath("$.tool_choice.type").value("image_generation"))
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
    void editsCurrentBitmapWithMaskAndPreservesTransparentOutput() throws IOException {
        RestClient.Builder builder=RestClient.builder();
        MockRestServiceServer server=MockRestServiceServer.bindTo(builder).build();
        OpenAiResponsesClient client=client(builder);
        var raster=new BufferedImage(16,16,BufferedImage.TYPE_INT_ARGB);
        raster.setRGB(1,1,0x80112233);
        byte[] png=com.tovarika.tech.images.application.ImageRaster.png(raster);
        String encoded=Base64.getEncoder().encodeToString(png);
        server.expect(requestTo("https://api.openai.test/v1/responses"))
                .andExpect(jsonPath("$.store").value(false))
                .andExpect(jsonPath("$.input[0].content.length()").value(2))
                .andExpect(jsonPath("$.input[0].content[0].text").value("Remove background"))
                .andExpect(jsonPath("$.input[0].content[1].image_url").value("data:image/png;base64,"+encoded))
                .andExpect(jsonPath("$.tools[0].action").value("edit"))
                .andExpect(jsonPath("$.tools[0].background").value("transparent"))
                .andExpect(jsonPath("$.tool_choice.type").value("image_generation"))
                .andExpect(jsonPath("$.tools[0].output_format").value("png"))
                .andExpect(jsonPath("$.tools[0].input_image_mask.image_url").value("data:image/png;base64,"+encoded))
                .andRespond(withSuccess("{\"status\":\"completed\",\"output\":[{\"type\":\"image_generation_call\",\"result\":\""+encoded+"\"}]}",MediaType.APPLICATION_JSON));
        var result=client.edit("vision-test","image-test",new com.tovarika.tech.images.application.ImageEditInput(
                png,"image/png","Remove background",16,16,png,true));
        assertThat(com.tovarika.tech.images.application.ImageRaster.decode(result.bytes()).getRGB(1,1)>>>24).isEqualTo(128);
        assertThat(result.width()).isEqualTo(16);assertThat(result.height()).isEqualTo(16);
        server.verify();
    }

    @Test
    void sanitizesIncompleteEditResponseWithoutProviderText() throws IOException {
        RestClient.Builder builder=RestClient.builder();
        MockRestServiceServer server=MockRestServiceServer.bindTo(builder).build();
        OpenAiResponsesClient client=client(builder);
        server.expect(requestTo("https://api.openai.test/v1/responses"))
                .andRespond(withSuccess("{\"status\":\"failed\",\"private\":\"provider details\"}",MediaType.APPLICATION_JSON));
        assertThatThrownBy(()->client.edit("vision-test","image-test",new com.tovarika.tech.images.application.ImageEditInput(
                TemplatePlaceholder.png(),"image/png","Edit",16,16,new byte[0],false)))
                .hasMessage("OpenAI image edit request failed").hasMessageNotContaining("provider details");
        server.verify();
    }

    @Test
    void diagnosesCompletedTextOnlyEditWithoutExposingProviderText() throws IOException {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        OpenAiResponsesClient client = client(builder);
        server.expect(requestTo("https://api.openai.test/v1/responses"))
                .andExpect(jsonPath("$.tool_choice.type").value("image_generation"))
                .andRespond(withSuccess("{\"status\":\"completed\",\"output\":[{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"provider details\"}]}]}", MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> client.edit("vision-test", "image-test", new com.tovarika.tech.images.application.ImageEditInput(
                TemplatePlaceholder.png(), "image/png", "Redraw the card in a different layout", 16, 16, new byte[0], false)))
                .isInstanceOf(OpenAiResponsesClient.ImageEditFailure.class)
                .hasMessage("OpenAI image edit request failed").hasNoCause()
                .extracting(failure -> ((OpenAiResponsesClient.ImageEditFailure) failure).reason())
                .isEqualTo(OpenAiResponsesClient.ImageEditFailure.Reason.NO_IMAGE_RESULT);
        server.verify();
    }

    @Test
    void diagnosesProviderHttpStatusWithoutExposingItsErrorBody() throws IOException {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        OpenAiResponsesClient client = client(builder);
        server.expect(requestTo("https://api.openai.test/v1/responses"))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators
                        .withStatus(org.springframework.http.HttpStatus.BAD_REQUEST)
                        .contentType(MediaType.APPLICATION_JSON).body("{\"error\":{\"message\":\"provider details\"}}"));
        assertThatThrownBy(() -> client.edit("vision-test", "image-test", new com.tovarika.tech.images.application.ImageEditInput(
                TemplatePlaceholder.png(), "image/png", "Redraw the card in a different layout", 16, 16, new byte[0], false)))
                .isInstanceOf(OpenAiResponsesClient.ImageEditFailure.class)
                .hasMessage("OpenAI image edit request failed").hasNoCause()
                .satisfies(failure -> {
                    var diagnostic = (OpenAiResponsesClient.ImageEditFailure) failure;
                    assertThat(diagnostic.reason()).isEqualTo(OpenAiResponsesClient.ImageEditFailure.Reason.HTTP_ERROR);
                    assertThat(diagnostic.httpStatus()).isEqualTo(400);
                });
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

    @ParameterizedTest
    @CsvSource({
        "1024,1024,1024x1024", "1152,1536,1152x1536",
        "1024,1280,1024x1280", "1536,864,1536x864"
    })
    void requestsExactCardDimensionsWhenModelSupportsThem(int width, int height, String toolSize) throws IOException {
        assertToolSize("gpt-image-2.5-sunburst", width, height, toolSize);
    }

    @ParameterizedTest
    @ValueSource(strings = {"gpt-image-2", "gpt-image-2-2026-04-21",
            "gpt-image-2.5-sunburst-2026-09-08", "gpt-image-2.5-flare", "gpt-image-2.5-flare-2026-09-08"})
    void supportsCustomSizeModelAliasesAndSnapshots(String model) throws IOException {
        assertToolSize(model, 1536, 864, "1536x864");
    }

    @ParameterizedTest
    @CsvSource({
        "gpt-image-1.5,1024,1024,1024x1024", "gpt-image-1.5,1152,1536,1024x1536",
        "gpt-image-1.5,1024,1280,1024x1536", "gpt-image-1.5,1536,864,1536x1024",
        "gpt-image-2.5-sunburst,1001,1280,1024x1536",
        "gpt-image-2.5-sunburst,256,256,1024x1024",
        "gpt-image-2.5-sunburst,3072,512,1536x1024",
        "gpt-image-2.5-sunburst,3072,3072,1024x1024"
    })
    void fallsBackToStandardToolSizes(String model, int width, int height, String toolSize) throws IOException {
        assertToolSize(model, width, height, toolSize);
    }

    @ParameterizedTest
    @CsvSource({
        // Source size, final size, fitted content size and position. Include unexpected response orientations.
        "1024,1024,1024,1024,1024,1024,0,0",
        "1024,1536,1152,1536,1024,1536,64,0",
        "1024,1536,1024,1280,853,1280,85,0",
        "1536,1024,1536,864,1296,864,120,0",
        "1024,1024,1536,864,864,864,336,0",
        "1024,1536,1536,864,576,864,480,0",
        "1536,1024,1024,1280,1024,683,0,298"
    })
    void preservesAllFourCornersAndCentersFullCard(int sourceWidth, int sourceHeight,
            int width, int height, int contentWidth, int contentHeight, int x, int y) throws IOException {
        BufferedImage source = new BufferedImage(sourceWidth, sourceHeight, BufferedImage.TYPE_INT_RGB);
        var graphics = source.createGraphics();
        try {
            graphics.setColor(Color.GRAY);
            graphics.fillRect(0, 0, sourceWidth, sourceHeight);
            graphics.setColor(Color.RED);
            graphics.fillRect(0, 0, 64, 64);
            graphics.setColor(Color.GREEN);
            graphics.fillRect(sourceWidth - 64, 0, 64, 64);
            graphics.setColor(Color.BLUE);
            graphics.fillRect(0, sourceHeight - 64, 64, 64);
            graphics.setColor(Color.MAGENTA);
            graphics.fillRect(sourceWidth - 64, sourceHeight - 64, 64, 64);
            graphics.setColor(Color.CYAN);
            graphics.fillRect(sourceWidth / 2 - 40, sourceHeight / 2 - 40, 80, 80);
        } finally {
            graphics.dispose();
        }
        BufferedImage result = generatedResult(source, width, height);

        assertThat(result.getRGB(x + 8, y + 8)).isEqualTo(Color.RED.getRGB());
        assertThat(result.getRGB(x + contentWidth - 9, y + 8)).isEqualTo(Color.GREEN.getRGB());
        assertThat(result.getRGB(x + 8, y + contentHeight - 9)).isEqualTo(Color.BLUE.getRGB());
        assertThat(result.getRGB(x + contentWidth - 9, y + contentHeight - 9)).isEqualTo(Color.MAGENTA.getRGB());
        assertThat(result.getRGB(width / 2, height / 2)).isEqualTo(Color.CYAN.getRGB());
        if (x > 0) {
            assertThat(result.getRGB(x - 1, height / 2)).isEqualTo(Color.WHITE.getRGB());
            assertThat(result.getRGB(x + contentWidth, height / 2)).isEqualTo(Color.WHITE.getRGB());
        }
        if (y > 0) {
            assertThat(result.getRGB(width / 2, y - 1)).isEqualTo(Color.WHITE.getRGB());
            assertThat(result.getRGB(width / 2, y + contentHeight)).isEqualTo(Color.WHITE.getRGB());
        }
    }

    @Test
    void flattensTransparencyOntoWhiteInsteadOfBlack() throws IOException {
        BufferedImage source = new BufferedImage(1024, 1024, BufferedImage.TYPE_INT_ARGB);
        source.setRGB(512, 512, Color.RED.getRGB());
        BufferedImage result = generatedResult(source, 1024, 1024);
        assertThat(result.getRGB(0, 0)).isEqualTo(Color.WHITE.getRGB());
        assertThat(result.getRGB(512, 512)).isEqualTo(Color.RED.getRGB());
    }

    private void assertToolSize(String model, int width, int height, String toolSize) throws IOException {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        OpenAiResponsesClient client = client(builder);
        server.expect(requestTo("https://api.openai.test/v1/responses"))
                .andExpect(jsonPath("$.tools[0].size").value(toolSize))
                .andExpect(jsonPath("$.instructions").value(org.hamcrest.Matchers.containsString(width + "x" + height)))
                .andExpect(jsonPath("$.instructions").value(org.hamcrest.Matchers.containsString("5% inset")))
                .andRespond(imageResponse(TemplatePlaceholder.png()));
        client.generate("vision-test", model, request(width, height));
        server.verify();
    }

    private BufferedImage generatedResult(BufferedImage source, int width, int height) throws IOException {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        OpenAiResponsesClient client = client(builder);
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        ImageIO.write(source, "png", encoded);
        server.expect(requestTo("https://api.openai.test/v1/responses"))
                .andRespond(imageResponse(encoded.toByteArray()));
        var generated = client.generate("vision-test", "image-test", request(width, height));
        BufferedImage result = ImageIO.read(new ByteArrayInputStream(generated.bytes()));
        assertThat(generated.mediaType()).isEqualTo("image/png");
        assertThat(generated.width()).isEqualTo(width);
        assertThat(generated.height()).isEqualTo(height);
        assertThat(result.getWidth()).isEqualTo(width);
        assertThat(result.getHeight()).isEqualTo(height);
        server.verify();
        return result;
    }

    private org.springframework.test.web.client.ResponseCreator imageResponse(byte[] png) {
        return withSuccess("""
                {"status":"completed","output":[{"type":"image_generation_call","result":"%s"}]}
                """.formatted(Base64.getEncoder().encodeToString(png)), MediaType.APPLICATION_JSON);
    }

    private GenerationRequest request(int width, int height) {
        return new GenerationRequest("Create a card", List.of(
                new GenerationRequest.InputImage(TemplatePlaceholder.png(), "image/png", "product")), width, height);
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
