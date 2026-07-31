package net.shieldshare.shieldshare.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.LocalDateTime;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(OversizedPayloadException.class)
    public ResponseEntity<ErrorResponse> handleOversizedPayloadException(OversizedPayloadException e) {
        HttpStatus status = HttpStatus.CONTENT_TOO_LARGE;
        ErrorResponse response = new ErrorResponse(LocalDateTime.now(), status.value(), status.getReasonPhrase(), e.getMessage());
        return new ResponseEntity<>(response, status);
    }
}
