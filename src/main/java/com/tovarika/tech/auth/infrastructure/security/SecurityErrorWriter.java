package com.tovarika.tech.auth.infrastructure.security;

import com.tovarika.api.publicapi.model.ErrorCodeDto;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class SecurityErrorWriter {

    private final ObjectMapper objectMapper;

    public SecurityErrorWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void write(
            HttpServletRequest request,
            HttpServletResponse response,
            int status,
            ErrorCodeDto code,
            String safeMessage)
            throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        String requestId = (String) request.getAttribute(RequestIdFilter.ATTRIBUTE);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("code", code.getValue());
        payload.put("message", safeMessage);
        payload.put("requestId", requestId == null ? "req_unknown" : requestId);
        objectMapper.writeValue(response.getOutputStream(), payload);
    }
}
