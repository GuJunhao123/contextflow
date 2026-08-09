package com.contextflow;

import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.HttpRequestMethodNotSupportedException;

@RestControllerAdvice
public class ApiExceptionHandler {
    private final ApiController api;
    public ApiExceptionHandler(ApiController api) { this.api = api; }

    @ExceptionHandler(ApiController.ApiFailure.class)
    public ResponseEntity<Contracts.Result> failure(ApiController.ApiFailure e) {
        return ResponseEntity.status(e.status).body(e.result);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Contracts.Result> malformed() {
        return ResponseEntity.badRequest().body(api.error("INVALID_JSON", "请求必须为合法JSON，year必须为整数。"));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Contracts.Result> method() {
        return ResponseEntity.status(405).body(api.error("METHOD_NOT_ALLOWED", "不支持该HTTP方法。"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Contracts.Result> unexpected(Exception ignored) {
        return ResponseEntity.internalServerError().body(api.error("INTERNAL_ERROR", "请求失败，请通过requestId排查。"));
    }
}
