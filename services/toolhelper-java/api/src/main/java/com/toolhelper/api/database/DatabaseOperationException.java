package com.toolhelper.api.database;

/** 保留底层异常原因，同时向 API 层暴露稳定的数据库错误码。 */
public final class DatabaseOperationException extends RuntimeException {
    private final DatabaseErrorCode code;

    public DatabaseOperationException(DatabaseErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public DatabaseOperationException(DatabaseErrorCode code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public DatabaseErrorCode code() {
        return code;
    }
}
