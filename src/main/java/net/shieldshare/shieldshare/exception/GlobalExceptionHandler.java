package net.shieldshare.shieldshare.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    // Generate a uniform error response to send to the client
    private ResponseEntity<ErrorResponse> generateErrorResponse(HttpStatus status, Exception e) {
        ErrorResponse response = new ErrorResponse(LocalDateTime.now(), status.value(), status.getReasonPhrase(), e.getMessage());
        return new ResponseEntity<>(response, status);
    }

    // Generic fallback handler for any exception not listed in this class
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleException(Exception e) {
        log.error("Generic exception handler was invoked. Unexpected error: {}", e.getMessage(), e);
        return generateErrorResponse(HttpStatus.INTERNAL_SERVER_ERROR, e);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleMethodArgumentNotValidException(MethodArgumentNotValidException e) {
        HttpStatus status = HttpStatus.BAD_REQUEST;
        Map<String, String> errors = e.getBindingResult().getFieldErrors().stream()
                .collect(Collectors.toMap(FieldError::getField, FieldError::getDefaultMessage));
        ErrorResponse response = new ErrorResponse(LocalDateTime.now(), status.value(), status.getReasonPhrase(), errors.toString());
        return new ResponseEntity<>(response, status);
    }

    @ExceptionHandler(OversizedPayloadException.class)
    public ResponseEntity<ErrorResponse> handleOversizedPayloadException(OversizedPayloadException e) {
        return generateErrorResponse(HttpStatus.CONTENT_TOO_LARGE, e);
    }

    @ExceptionHandler(SecretInsertionException.class)
    public ResponseEntity<ErrorResponse> handleSecretInsertionException(SecretInsertionException e) {
        return generateErrorResponse(HttpStatus.INTERNAL_SERVER_ERROR, e);
    }

    @ExceptionHandler(MalformedPayloadException.class)
    public ResponseEntity<ErrorResponse> handleMalformedPayloadException(MalformedPayloadException e) {
        return generateErrorResponse(HttpStatus.BAD_REQUEST, e);
    }

    @ExceptionHandler(InvalidSecretException.class)
    public ResponseEntity<ErrorResponse> handleInvalidSecretException(InvalidSecretException e) {
        return generateErrorResponse(HttpStatus.NOT_FOUND, e);
    }

    /**
     * This method handles the case where a request passes through
     * {@link net.shieldshare.shieldshare.filter.payloadsize.RequestSizeFilter} and is oversized. Because the
     * {@code OversizedPayloadException} gets thrown while Jackson is reading the input stream, Spring wraps the
     * exception in an {@code HttpMessageNotReadableException}. Here, we walk the cause chain looking to see if it was
     * caused by an {@code OversizedPayloadException}, and return 413 accordingly. Otherwise, we return 400.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleHttpMessageNotReadableException(HttpMessageNotReadableException e) {
        Throwable root = e;
        while (root.getCause() != null) {
            Throwable cause = root.getCause();
            if (cause instanceof OversizedPayloadException ope) {
                return generateErrorResponse(HttpStatus.CONTENT_TOO_LARGE, ope);
            }
            root = cause;
        }
        return generateErrorResponse(HttpStatus.BAD_REQUEST, e);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleHttpRequestMethodNotSupportedException(HttpRequestMethodNotSupportedException e) {
        return generateErrorResponse(HttpStatus.METHOD_NOT_ALLOWED, e);
    }
}
