package net.shieldshare.shieldshare.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.stream.Collectors;

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
}
