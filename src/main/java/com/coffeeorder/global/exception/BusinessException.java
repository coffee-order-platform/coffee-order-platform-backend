package com.coffeeorder.global.exception;

import lombok.Getter;

/** 업무 규칙을 어겼을 때 던지는 예외. 응답은 ErrorCode 가 전부 결정한다. */
@Getter
public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;

    public BusinessException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }
}
