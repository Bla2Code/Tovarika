package com.tovarika.tech.cards.editing.api;

import com.tovarika.api.publicapi.model.ImageEditRequestDto;
import com.tovarika.api.publicapi.model.UndoCardImageRequestDto;
import com.tovarika.api.publicapi.model.RedoCardImageRequestDto;
import com.tovarika.tech.cards.CardsController;
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

/** Enforce additionalProperties/const even when Java codegen cannot express those JSON Schema rules. */
@ControllerAdvice(assignableTypes=CardsController.class)
class ImageEditBodyAdvice extends RequestBodyAdviceAdapter {
    private final ObjectMapper mapper;
    ImageEditBodyAdvice(ObjectMapper mapper) { this.mapper=mapper; }
    public boolean supports(MethodParameter parameter,Type type,Class<? extends HttpMessageConverter<?>> converter) {
        return type==ImageEditRequestDto.class || type==UndoCardImageRequestDto.class || type==RedoCardImageRequestDto.class;
    }
    public HttpInputMessage beforeBodyRead(HttpInputMessage message,MethodParameter parameter,Type type,
            Class<? extends HttpMessageConverter<?>> converter) throws IOException {
        byte[] body=message.getBody().readNBytes(1048577);JsonNode root;
        if(body.length>1048576) throw new ApiFailure(400,"VALIDATION_ERROR","Image request exceeds 1 MiB");
        try { root=mapper.readTree(body); } catch(tools.jackson.core.JacksonException invalid) { throw new ApiFailure(400,"VALIDATION_ERROR","Invalid JSON request"); }
        boolean history=type==UndoCardImageRequestDto.class || type==RedoCardImageRequestDto.class;
        exact(root,history?Set.of("baseVersionId","expectedImageRevision"):Set.of("baseVersionId","expectedImageRevision","operation"));
        if(!root.path("baseVersionId").isString() || !root.path("expectedImageRevision").isIntegralNumber()
                || !root.path("expectedImageRevision").canConvertToLong() || root.path("expectedImageRevision").asLong()<1) throw invalid();
        if(!history) {
            var op=root.path("operation");String kind=op.path("kind").asText();
            switch(kind) {
                case "entire" -> {exact(op,Set.of("kind","prompt"));text(op,"prompt");}
                case "region" -> {
                    exact(op,Set.of("kind","prompt","region"));text(op,"prompt");var region=op.path("region");
                    exact(region,Set.of("kind","rect"));if(!"rectangle".equals(region.path("kind").asText())) throw invalid();
                    var rect=region.path("rect");exact(rect,Set.of("x","y","width","height"));
                    for(String field:Set.of("x","y","width","height")) if(!rect.path(field).isNumber()) throw invalid();
                }
                case "erase" -> {
                    exact(op,Set.of("kind","mask"));var mask=op.path("mask");
                    exact(mask,Set.of("kind","strokes"));
                    if(!"brush".equals(mask.path("kind").asText())) throw invalid();
                    var strokes=mask.path("strokes");
                    if(!strokes.isArray() || strokes.isEmpty() || strokes.size()>64) throw invalid();
                    int total=0;
                    for(var stroke:strokes) {
                        exact(stroke,Set.of("radius","points"));
                        if(!stroke.path("radius").isNumber()) throw invalid();
                        var points=stroke.path("points");
                        if(!points.isArray() || points.isEmpty() || points.size()>1024) throw invalid();
                        total+=points.size();if(total>8192) throw invalid();
                        for(var point:points) {
                            exact(point,Set.of("x","y"));
                            if(!point.path("x").isNumber() || !point.path("y").isNumber()) throw invalid();
                        }
                    }
                }
                case "remove_background" -> {exact(op,Set.of("kind","foreground"));text(op,"foreground");}
                case "resize" -> {exact(op,Set.of("kind","aspectRatio","mode"));text(op,"aspectRatio");text(op,"mode");}
                default -> throw invalid();
            }
        }
        return new HttpInputMessage() {
            public InputStream getBody() {return new ByteArrayInputStream(body);}
            public HttpHeaders getHeaders() {return message.getHeaders();}
        };
    }
    private void text(JsonNode node,String field) {if(!node.path(field).isString()) throw invalid();}
    private void exact(JsonNode node,Set<String> fields) {
        if(node==null || !node.isObject() || node.size()!=fields.size()) throw invalid();
        for(var entry:node.properties()) if(!fields.contains(entry.getKey()) || entry.getValue().isNull()) throw invalid();
    }
    private ApiFailure invalid() {return new ApiFailure(422,"VALIDATION_ERROR","Image request does not match contract");}
}
