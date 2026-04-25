package com.velvet.backend.exception;

import lombok.Getter;

@Getter
public class AppException extends RuntimeException {
    private final String code;

    public AppException(String code, String message) {
        super(message);
        this.code = code;
    }
}
