package com.coffeeorder.global.common;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/** 성공 응답. 필드는 status / message / data (에러 응답과 같은 모양, 순서는 계약이라 명시). */
@JsonPropertyOrder({"status", "message", "data"})
public record ApiResponse<T>(int status, String message, T data) {

    public static <T> ApiResponse<T> ok(String message, T data) {
        return new ApiResponse<>(200, message, data);
    }

    /** 새 자원(주문·환불)을 만든 요청. 컨트롤러에 @ResponseStatus(CREATED) 를 함께 붙여 HTTP 상태와 맞춘다. */
    public static <T> ApiResponse<T> created(String message, T data) {
        return new ApiResponse<>(201, message, data);
    }
}
