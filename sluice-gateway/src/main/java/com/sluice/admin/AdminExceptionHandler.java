package com.sluice.admin;

import com.sluice.config.ConfigValidationException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NoSuchElementException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class AdminExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(AdminExceptionHandler.class);

    @ExceptionHandler(ConfigValidationException.class)
    public ResponseEntity<Map<String, Object>> invalidConfig(ConfigValidationException e) {
        Map<String, Object> inner = new LinkedHashMap<>();
        inner.put("code", "invalid_config");
        inner.put("message", "the configuration was rejected and the active config is unchanged");
        inner.put("status", HttpStatus.UNPROCESSABLE_ENTITY.value());
        inner.put("errors", e.errors());
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of("error", inner));
    }

    @ExceptionHandler({NotFoundException.class, NoSuchElementException.class})
    public ResponseEntity<Map<String, Object>> notFound(RuntimeException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error("not_found", e.getMessage(), HttpStatus.NOT_FOUND));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Map<String, Object>> noResource(NoResourceFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(error("not_found", "no such admin endpoint", HttpStatus.NOT_FOUND));
    }

    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<Map<String, Object>> conflict(ConflictException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(error("conflict", e.getMessage(), HttpStatus.CONFLICT));
    }

    @ExceptionHandler({IllegalArgumentException.class, HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class})
    public ResponseEntity<Map<String, Object>> badRequest(Exception e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(error("bad_request", e.getMessage(), HttpStatus.BAD_REQUEST));
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<Map<String, Object>> storeUnavailable(DataAccessException e) {
        LOG.warn("backing store failure: {}", e.toString());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(error("store_unavailable", "a backing store is unavailable", HttpStatus.SERVICE_UNAVAILABLE));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> unexpected(Exception e) {
        LOG.error("admin request failed", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(error("internal_error", "the admin api failed to process the request",
                        HttpStatus.INTERNAL_SERVER_ERROR));
    }

    private static Map<String, Object> error(String code, String message, HttpStatus status) {
        Map<String, Object> inner = new LinkedHashMap<>();
        inner.put("code", code);
        inner.put("message", message);
        inner.put("status", status.value());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", inner);
        return body;
    }
}
