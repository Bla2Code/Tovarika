package com.tovarika.tech.auth.api;

import com.tovarika.api.publicapi.model.ApiErrorDto;
import com.tovarika.api.publicapi.model.ErrorCodeDto;
import com.tovarika.tech.auth.application.AuthException;
import com.tovarika.tech.auth.infrastructure.security.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(AuthException.class)
    ResponseEntity<ApiErrorDto> handleAuth(AuthException exception, HttpServletRequest request) {
        ErrorCodeDto code = ErrorCodeDto.fromValue(exception.code().name());
        return ResponseEntity.status(exception.status())
                .body(error(code, exception.getMessage(), request));
    }

    @ExceptionHandler({
        MethodArgumentNotValidException.class,
        ConstraintViolationException.class,
        HttpMessageNotReadableException.class
    })
    ResponseEntity<ApiErrorDto> handleValidation(Exception exception, HttpServletRequest request) {
        return ResponseEntity.badRequest()
                .body(error(ErrorCodeDto.VALIDATION_ERROR, "Request validation failed", request));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiErrorDto> handleUnexpected(Exception exception, HttpServletRequest request) {
        String requestId = requestId(request);
        // Exception messages and stack traces may contain provider URLs or credentials; log only safe metadata.
        log.error("Unhandled authentication request failure requestId={} type={}", requestId, exception.getClass().getName());
        return ResponseEntity.internalServerError()
                .body(new ApiErrorDto(ErrorCodeDto.INTERNAL_ERROR, "Internal server error", requestId));
    }

    private ApiErrorDto error(ErrorCodeDto code, String message, HttpServletRequest request) {
        return new ApiErrorDto(code, message, requestId(request));
    }

    private String requestId(HttpServletRequest request) {
        Object requestId = request.getAttribute(RequestIdFilter.ATTRIBUTE);
        return requestId == null ? "req_unknown" : requestId.toString();
    }
}
