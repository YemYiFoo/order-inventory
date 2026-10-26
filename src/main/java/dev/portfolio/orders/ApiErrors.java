package dev.portfolio.orders;

import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.Map;

/** Maps business, framework and database failures to safe JSON errors. */
@RestControllerAdvice
public class ApiErrors {

    @ExceptionHandler(ApiException.class)
    ResponseEntity<?> business(ApiException e) {
        return ResponseEntity.status(e.status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body(e.code, e.getMessage()));
    }

    @ExceptionHandler({
        MethodArgumentTypeMismatchException.class,
        MissingServletRequestParameterException.class
    })
    ResponseEntity<?> invalid(Exception e) {
        return ResponseEntity.badRequest()
                .contentType(MediaType.APPLICATION_JSON)
                .body(body("INVALID_REQUEST", "Invalid parameter"));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<?> method(HttpRequestMethodNotSupportedException e) {
        return ResponseEntity.status(405)
                .headers(e.getHeaders())
                .contentType(MediaType.APPLICATION_JSON)
                .body(body("METHOD_NOT_ALLOWED", "Method not allowed for this resource"));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<?> missingRoute(Exception e) {
        return business(ApiException.missing());
    }

    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    ResponseEntity<?> responseFormat(Exception e) {
        // Error bodies stay JSON, even when Accept cannot be satisfied by a normal response.
        return ResponseEntity.status(406)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body("NOT_ACCEPTABLE", "Response is available as application/json"));
    }

    @ExceptionHandler({
        TransientDataAccessException.class,
        CannotCreateTransactionException.class,
        DataAccessResourceFailureException.class,
        RecoverableDataAccessException.class
    })
    ResponseEntity<?> temporary(Exception e) {
        return ResponseEntity.status(503)
                .header("Retry-After", "1")
                .contentType(MediaType.APPLICATION_JSON)
                .body(
                        body(
                                "TEMPORARY_DATABASE_FAILURE",
                                "Database temporarily unavailable; retry with the same key"));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<?> unexpected(Exception e) {
        LoggerFactory.getLogger(ApiErrors.class).error("Request failed", e);
        return ResponseEntity.internalServerError()
                .contentType(MediaType.APPLICATION_JSON)
                .body(body("UNEXPECTED_ERROR", "Unexpected server error"));
    }

    static Map<String, String> body(String code, String message) {
        return Map.of("code", code, "message", message);
    }

    static boolean temporaryDatabaseFailure(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof TransientDataAccessException
                    || cause instanceof CannotCreateTransactionException
                    || cause instanceof DataAccessResourceFailureException
                    || cause instanceof RecoverableDataAccessException) {
                return true;
            }
        }
        return false;
    }
}
