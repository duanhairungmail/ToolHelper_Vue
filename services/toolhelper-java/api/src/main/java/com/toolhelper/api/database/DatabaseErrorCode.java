package com.toolhelper.api.database;

import org.springframework.http.HttpStatus;

/** 数据库 API 对外稳定错误码及对应 HTTP 状态。 */
public enum DatabaseErrorCode {
    SQLITE_LOCKED("SQLITE_LOCKED", HttpStatus.CONFLICT),
    DATABASE_READ_ONLY("DATABASE_READ_ONLY", HttpStatus.FORBIDDEN),
    DATABASE_CORRUPT("DATABASE_CORRUPT", HttpStatus.UNPROCESSABLE_ENTITY),
    DISK_FULL("DISK_FULL", HttpStatus.INSUFFICIENT_STORAGE),
    DATABASE_PATH_INVALID("DATABASE_PATH_INVALID", HttpStatus.BAD_REQUEST),
    DATABASE_SESSION_CLOSED("DATABASE_SESSION_CLOSED", HttpStatus.NOT_FOUND),
    DATABASE_OPERATION_FAILED("DATABASE_OPERATION_FAILED", HttpStatus.BAD_REQUEST),
    DATABASE_REQUEST_INVALID("DATABASE_REQUEST_INVALID", HttpStatus.BAD_REQUEST);

    private final String value;
    private final HttpStatus httpStatus;

    DatabaseErrorCode(String value, HttpStatus httpStatus) {
        this.value = value;
        this.httpStatus = httpStatus;
    }

    public String value() {
        return value;
    }

    public HttpStatus httpStatus() {
        return httpStatus;
    }
}
