package com.tovarika.tech.project;

import com.tovarika.api.publicapi.model.UpdateProjectRequestDto;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Type;
import java.util.Set;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdviceAdapter;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Enforces schema constraints lost by codegen for optional, non-null PATCH properties. */
@ControllerAdvice(assignableTypes = ProjectsController.class)
class ProjectPatchBodyAdvice extends RequestBodyAdviceAdapter {
    private static final Set<String> FIELDS = Set.of("name", "selectedTemplateId", "defaultAspectRatio");
    private final ObjectMapper mapper;

    ProjectPatchBodyAdvice(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public boolean supports(MethodParameter parameter, Type targetType,
            Class<? extends HttpMessageConverter<?>> converterType) {
        return targetType == UpdateProjectRequestDto.class;
    }

    @Override
    public HttpInputMessage beforeBodyRead(HttpInputMessage message, MethodParameter parameter,
            Type targetType, Class<? extends HttpMessageConverter<?>> converterType) throws IOException {
        byte[] body = message.getBody().readAllBytes();
        JsonNode node;
        try {
            node = mapper.readTree(body);
        } catch (tools.jackson.core.JacksonException invalid) {
            throw ProjectException.badRequest("Request validation failed");
        }
        if (node == null || !node.isObject() || node.isEmpty()
                || node.properties().stream().anyMatch(entry ->
                        !FIELDS.contains(entry.getKey()) || !entry.getValue().isString())) {
            throw ProjectException.badRequest("Provide non-null project settings only");
        }
        return new HttpInputMessage() {
            @Override
            public InputStream getBody() {
                return new ByteArrayInputStream(body);
            }

            @Override
            public HttpHeaders getHeaders() {
                return message.getHeaders();
            }
        };
    }
}
