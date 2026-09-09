package com.tovarika.tech.project;

import com.tovarika.api.publicapi.model.ErrorCodeDto;

public final class ProjectException extends RuntimeException {
    private final ErrorCodeDto code;
    private final int status;

    private ProjectException(ErrorCodeDto code, int status, String message) {
        super(message);
        this.code = code;
        this.status = status;
    }

    public ErrorCodeDto code() {
        return code;
    }

    public int status() {
        return status;
    }

    static ProjectException unauthorized(ErrorCodeDto code, String message) {
        return new ProjectException(code, 401, message);
    }

    static ProjectException forbidden(String message) {
        return new ProjectException(ErrorCodeDto.FORBIDDEN, 403, message);
    }

    static ProjectException productNotFound() {
        return new ProjectException(ErrorCodeDto.PRODUCT_NOT_FOUND, 404, "Product not found");
    }

    static ProjectException projectNotFound() {
        return new ProjectException(ErrorCodeDto.PROJECT_NOT_FOUND, 404, "Project not found");
    }

    static ProjectException conflict() {
        return new ProjectException(ErrorCodeDto.IDEMPOTENCY_CONFLICT, 409, "A project already exists for this product");
    }

    static ProjectException badRequest(String message) {
        return new ProjectException(ErrorCodeDto.VALIDATION_ERROR, 400, message);
    }
}
