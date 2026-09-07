package com.toolhelper.application.crypto;

public final class AesOperationException extends RuntimeException {
    private final String code;

    public AesOperationException(String code, String message) {
        super(message);
        this.code = code;
    }

    public AesOperationException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
