package net.shieldshare.shieldshare.exception;

public class OversizedPayloadException extends RuntimeException {
    public OversizedPayloadException(String message) {
        super(message);
    }
}
