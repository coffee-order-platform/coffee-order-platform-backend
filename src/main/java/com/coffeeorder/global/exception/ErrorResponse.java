package com.coffeeorder.global.exception;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * 에러 응답. 필드는 status / code / message 3개만 허용한다(작업 가이드 규칙).
 * Jackson 3 은 필드를 알파벳순으로 정렬하므로 순서를 명시한다.
 */
@JsonPropertyOrder({"status", "code", "message"})
public record ErrorResponse(int status, String code, String message) {

    public static ErrorResponse of(ErrorCode errorCode) {
        return new ErrorResponse(errorCode.getStatus().value(), errorCode.name(), errorCode.getMessage());
    }
}
