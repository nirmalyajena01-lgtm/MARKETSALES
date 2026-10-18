package com.supermarket.exception;

/**
 * Exception thrown when domain validation rules are violated, such as
 * non-positive quantities, invalid prices, or empty cart checkout attempts.
 */
public class ValidationException extends RuntimeException {
    public ValidationException(String message) {
        super(message);
    }

    public ValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}
