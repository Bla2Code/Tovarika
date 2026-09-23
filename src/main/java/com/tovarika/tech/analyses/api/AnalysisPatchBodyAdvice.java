package com.tovarika.tech.analyses.api;

import com.tovarika.api.publicapi.model.UpdateProductAnalysisRequestDto;
import com.tovarika.tech.shared.application.ApiFailure;
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

@ControllerAdvice(assignableTypes=AnalysesController.class)
class AnalysisPatchBodyAdvice extends RequestBodyAdviceAdapter {
    private static final Set<String> FIELDS=Set.of("title","description","idea");
    private final ObjectMapper mapper;
    AnalysisPatchBodyAdvice(ObjectMapper mapper) { this.mapper=mapper; }
    @Override
    public boolean supports(MethodParameter parameter,Type targetType,
            Class<? extends HttpMessageConverter<?>> converterType) {
        return targetType==UpdateProductAnalysisRequestDto.class;
    }
    @Override
    public HttpInputMessage beforeBodyRead(HttpInputMessage message,MethodParameter parameter,
            Type targetType,Class<? extends HttpMessageConverter<?>> converterType) throws IOException {
        byte[] body=message.getBody().readAllBytes();
        JsonNode node;
        try { node=mapper.readTree(body); }
        catch(tools.jackson.core.JacksonException invalid) {
            throw new ApiFailure(400,"VALIDATION_ERROR","Request validation failed");
        }
        if(node==null || !node.isObject() || node.isEmpty())
            throw new ApiFailure(400,"VALIDATION_ERROR","At least one editable field is required");
        for(var entry:node.properties()) {
            if(!FIELDS.contains(entry.getKey()) || !entry.getValue().isString())
                throw new ApiFailure(422,"VALIDATION_ERROR","Only title, description and idea can be edited");
            int length=entry.getValue().asString().length();
            if(entry.getKey().equals("title") && length>200
                    || entry.getKey().equals("description") && (length<1 || length>4000)
                    || entry.getKey().equals("idea") && (length<1 || length>2000))
                throw new ApiFailure(422,"VALIDATION_ERROR","Analysis field is outside contract limits");
        }
        return new HttpInputMessage() {
            public InputStream getBody() { return new ByteArrayInputStream(body); }
            public HttpHeaders getHeaders() { return message.getHeaders(); }
        };
    }
}
