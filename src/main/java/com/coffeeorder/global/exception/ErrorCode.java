package com.coffeeorder.global.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/** API 에러코드. name() 이 응답의 code, status 와 message 가 나머지 두 필드가 된다. */
@Getter
@RequiredArgsConstructor
public enum ErrorCode {

    // 업무 규칙 10개 = 작업 가이드 PDF 확정 7개(이름·상태를 바꾸지 않는다) + 11/18 추가 3개
    USER_NOT_FOUND(HttpStatus.NOT_FOUND, "사용자를 찾을 수 없습니다."),
    MENU_NOT_FOUND(HttpStatus.NOT_FOUND, "메뉴를 찾을 수 없습니다."),
    CART_ITEM_NOT_FOUND(HttpStatus.NOT_FOUND, "장바구니 항목을 찾을 수 없습니다."),
    INVALID_QUANTITY(HttpStatus.BAD_REQUEST, "수량이 올바르지 않습니다."),
    CART_EMPTY(HttpStatus.BAD_REQUEST, "장바구니가 비어 있습니다."),
    INSUFFICIENT_POINT(HttpStatus.BAD_REQUEST, "포인트가 부족합니다."),
    INVALID_CHARGE_AMOUNT(HttpStatus.BAD_REQUEST, "충전 금액이 올바르지 않습니다."),
    // 2026-11-18 추가: 장바구니 동시 수정이 재시도(2회) 후에도 충돌할 때
    CART_UPDATE_CONFLICT(HttpStatus.CONFLICT, "장바구니가 동시에 수정되었습니다. 다시 시도해 주세요."),
    // 2026-11-18 추가: 주문 환불(전체). 다른 사용자의 주문도 존재를 드러내지 않도록 ORDER_NOT_FOUND
    ORDER_NOT_FOUND(HttpStatus.NOT_FOUND, "주문을 찾을 수 없습니다."),
    ALREADY_REFUNDED(HttpStatus.BAD_REQUEST, "이미 환불된 주문입니다."),

    // 공통
    NOT_FOUND(HttpStatus.NOT_FOUND, "요청한 주소를 찾을 수 없습니다."),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "지원하지 않는 요청 방식입니다."),
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "지원하지 않는 요청 형식입니다."),
    INVALID_REQUEST(HttpStatus.BAD_REQUEST, "요청 값이 올바르지 않습니다."),
    INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 오류가 발생했습니다.");

    private final HttpStatus status;
    private final String message;
}
