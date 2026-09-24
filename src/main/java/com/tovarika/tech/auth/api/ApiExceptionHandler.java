package com.tovarika.tech.auth.api;

import com.tovarika.api.publicapi.model.ApiErrorDto;
import com.tovarika.api.publicapi.model.ErrorCodeDto;
import com.tovarika.tech.auth.application.AuthException;
import com.tovarika.tech.auth.infrastructure.security.RequestIdFilter;
import com.tovarika.tech.project.ProjectException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(com.tovarika.tech.shared.application.ApiFailure.class)
    ResponseEntity<ApiErrorDto> handleApi(com.tovarika.tech.shared.application.ApiFailure failure, HttpServletRequest request) {
        return ResponseEntity.status(failure.status()).body(error(ErrorCodeDto.fromValue(failure.code()), failure.getMessage(), request));
    }

    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
    ResponseEntity<ApiErrorDto> handleUploadSize(Exception failure, HttpServletRequest request) {
        return ResponseEntity.status(413).body(error(ErrorCodeDto.FILE_TOO_LARGE, "Image exceeds 10 MiB", request));
    }

    @ExceptionHandler(org.springframework.web.multipart.support.MissingServletRequestPartException.class)
    ResponseEntity<ApiErrorDto> handleMissingPart(Exception failure, HttpServletRequest request) {
        return ResponseEntity.badRequest().body(error(ErrorCodeDto.UPLOAD_VALIDATION_ERROR, "Image is required", request));
    }

    @ExceptionHandler(org.springframework.web.HttpMediaTypeNotSupportedException.class)
    ResponseEntity<ApiErrorDto> handleMediaType(Exception failure, HttpServletRequest request) {
        return ResponseEntity.status(415).body(error(ErrorCodeDto.UNSUPPORTED_FORMAT, "Unsupported media type", request));
    }

    @ExceptionHandler(org.springframework.web.multipart.MultipartException.class)
    ResponseEntity<ApiErrorDto> handleMultipart(Exception failure, HttpServletRequest request) {
        return ResponseEntity.badRequest().body(error(ErrorCodeDto.UPLOAD_VALIDATION_ERROR, "Invalid multipart request", request));
    }

    @ExceptionHandler(AuthException.class)
    ResponseEntity<ApiErrorDto> handleAuth(AuthException exception, HttpServletRequest request) {
        ErrorCodeDto code = ErrorCodeDto.fromValue(exception.code().name());
        ResponseEntity.BodyBuilder response = ResponseEntity.status(exception.status());
        if (exception.retryAfterSeconds() != null) {
            response.header(org.springframework.http.HttpHeaders.RETRY_AFTER, exception.retryAfterSeconds().toString());
        }
        return response.body(error(code, exception.getMessage(), request));
    }

    @ExceptionHandler(ProjectException.class)
    ResponseEntity<ApiErrorDto> handleProject(ProjectException exception, HttpServletRequest request) {
        return ResponseEntity.status(exception.status())
                .body(error(exception.code(), exception.getMessage(), request));
    }

    @ExceptionHandler({
        org.springframework.web.bind.MissingRequestHeaderException.class,
        MethodArgumentNotValidException.class,
        ConstraintViolationException.class,
        HttpMessageNotReadableException.class,
        HandlerMethodValidationException.class,
        MethodArgumentTypeMismatchException.class
    })
    ResponseEntity<ApiErrorDto> handleValidation(Exception exception, HttpServletRequest request) {
        return ResponseEntity.badRequest()
                .body(error(ErrorCodeDto.VALIDATION_ERROR, "Request validation failed", request));
    }

    @ExceptionHandler({
        org.springframework.web.servlet.NoHandlerFoundException.class,
        org.springframework.web.servlet.resource.NoResourceFoundException.class
    })
    ResponseEntity<ApiErrorDto> handleMissingRoute(Exception exception, HttpServletRequest request) {
        return ResponseEntity.status(404)
                .body(error(ErrorCodeDto.VALIDATION_ERROR, "Resource not found", request));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiErrorDto> handleUnexpected(Exception exception, HttpServletRequest request) {
        String requestId = requestId(request);
        // Exception messages and stack traces may contain provider URLs or credentials; log only safe metadata.
        log.error("Unhandled API request failure requestId={} type={}", requestId, exception.getClass().getName());
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
