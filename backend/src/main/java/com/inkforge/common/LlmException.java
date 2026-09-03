package com.inkforge.common;

/** Raised when a remote LLM provider call fails (network, HTTP error, streaming error). */
public class LlmException extends RuntimeException {

    public LlmException(String message) {
        super(message);
    }

    public LlmException(String message, Throwable cause) {
        super(message, cause);
    }
}
