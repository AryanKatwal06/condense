package com.condense.core;

/**
 * Thrown when a command's output exceeds the maximum allowed buffer size.
 */
public class OutputLimitExceededException extends RuntimeException {
    public OutputLimitExceededException(String message) {
        super(message);
    }
}
