package com.tovarika.tech.exports;

import com.tovarika.api.publicapi.model.CreateExportRequestDto;
import com.tovarika.tech.shared.application.ApiFailure;
import java.io.*;
import java.lang.reflect.Type;
import java.util.*;
import org.springframework.core.MethodParameter;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdviceAdapter;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Preserve JSON Schema uniqueness before generated Java Set fields discard duplicate IDs. */
@ControllerAdvice(assignableTypes=ExportsController.class)
class ExportBodyAdvice extends RequestBodyAdviceAdapter {
    private final ObjectMapper mapper;
    ExportBodyAdvice(ObjectMapper mapper) {this.mapper=mapper;}
    public boolean supports(MethodParameter parameter,Type type,Class<? extends HttpMessageConverter<?>> converter) {
        return type==CreateExportRequestDto.class;
    }
    public HttpInputMessage beforeBodyRead(HttpInputMessage message,MethodParameter parameter,Type type,
            Class<? extends HttpMessageConverter<?>> converter) throws IOException {
        byte[] body=message.getBody().readNBytes(16385);
        if(body.length>16384) throw invalid();
        JsonNode root;
        try {root=mapper.readTree(body);} catch(tools.jackson.core.JacksonException malformed) {throw invalid();}
        exact(root,Set.of("cardIds","format","packaging","expectedImages"));
        if(!root.path("format").isString() || !root.path("packaging").isString()) throw invalid();
        var ids=root.path("cardIds");var distinct=new HashSet<String>();
        if(!ids.isArray() || ids.isEmpty() || ids.size()>10) throw invalid();
        for(var id:ids) if(!id.isString() || !distinct.add(id.asText())) throw invalid();
        if(root.has("expectedImages")) {
            var images=root.path("expectedImages");if(!images.isArray() || images.size()!=ids.size()) throw invalid();
            for(var image:images) {
                exact(image,Set.of("cardId","versionId","imageRevision"));
                if(image.size()!=3 || !image.path("cardId").isString() || !image.path("versionId").isString()
                        || !image.path("imageRevision").isIntegralNumber() || !image.path("imageRevision").canConvertToLong()) throw invalid();
            }
        }
        return new HttpInputMessage() {
            public InputStream getBody() {return new ByteArrayInputStream(body);}
            public HttpHeaders getHeaders() {return message.getHeaders();}
        };
    }
    private void exact(JsonNode node,Set<String> allowed) {
        if(node==null || !node.isObject()) throw invalid();
        for(var field:node.properties()) if(!allowed.contains(field.getKey()) || field.getValue().isNull()) throw invalid();
    }
    private ApiFailure invalid() {return new ApiFailure(422,"VALIDATION_ERROR","Export request does not match contract");}
}
