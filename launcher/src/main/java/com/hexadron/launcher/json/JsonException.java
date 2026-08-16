package com.hexadron.launcher.json;

/** Thrown for malformed JSON or a type mismatch in a strict accessor. */
public class JsonException extends RuntimeException {
    public JsonException(String message) {
        super(message);
    }

    public JsonException(String message, Throwable cause) {
        super(message, cause);
    }
}
