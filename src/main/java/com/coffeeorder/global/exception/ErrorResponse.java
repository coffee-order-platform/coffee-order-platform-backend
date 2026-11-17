package com.coffeeorder.global.exception;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * 에러 응답. 필드는 status / code / message 3개만 허용한다(작업 가이드 규칙).
 * 필드 순서는 응답 계약이라 기본 설정에 맡기지 않고 명시해서 고정한다.
 */
@JsonPropertyOrder({"status", "code", "message"})
public record ErrorResponse(int status, String code, String message) {

    public static ErrorResponse of(ErrorCode errorCode) {
        return new ErrorResponse(errorCode.getStatus().value(), errorCode.name(), errorCode.getMessage());
    }
}
