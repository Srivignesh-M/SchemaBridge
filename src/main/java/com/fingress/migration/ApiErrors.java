package com.fingress.migration;

import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.sql.SQLException;
import java.util.Map;

@RestControllerAdvice
public class ApiErrors {
    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    ResponseEntity<?> malformed() { return ResponseEntity.badRequest().body(Map.of("message", "Malformed JSON or unsupported request field")); }
    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<?> invalid(IllegalArgumentException error) { return ResponseEntity.badRequest().body(Map.of("message", error.getMessage() == null ? "Invalid request" : error.getMessage())); }
    @ExceptionHandler(SQLException.class)
    ResponseEntity<?> database(SQLException error) { return ResponseEntity.unprocessableEntity().body(Map.of("message", "Database request failed (SQLState " + error.getSQLState() + ", code " + error.getErrorCode() + "). Check connection, permissions and database compatibility.")); }
    @ExceptionHandler(Exception.class)
    ResponseEntity<?> failure(Exception error) { return ResponseEntity.status(500).body(Map.of("message", "Request failed. Check the input format and service configuration. Connection details and SQL values are omitted from errors.")); }
}
