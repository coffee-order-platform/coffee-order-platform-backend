package com.coffeeorder.global.exception;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/** 에러코드 15개(업무 10 + 공통 5)의 이름·HTTP 상태·응답 문구가 바뀌면 실패한다. */
class ErrorCodeTest {

    @Test
    void 에러코드_15개의_이름과_HTTP_상태가_확정값과_같다() {
        Map<String, HttpStatus> expected = new LinkedHashMap<>();
        expected.put("USER_NOT_FOUND", HttpStatus.NOT_FOUND);
        expected.put("MENU_NOT_FOUND", HttpStatus.NOT_FOUND);
        expected.put("CART_ITEM_NOT_FOUND", HttpStatus.NOT_FOUND);
        expected.put("INVALID_QUANTITY", HttpStatus.BAD_REQUEST);
        expected.put("CART_EMPTY", HttpStatus.BAD_REQUEST);
        expected.put("INSUFFICIENT_POINT", HttpStatus.BAD_REQUEST);
        expected.put("INVALID_CHARGE_AMOUNT", HttpStatus.BAD_REQUEST);
        expected.put("CART_UPDATE_CONFLICT", HttpStatus.CONFLICT);
        expected.put("ORDER_NOT_FOUND", HttpStatus.NOT_FOUND);
        expected.put("ALREADY_REFUNDED", HttpStatus.BAD_REQUEST);
        expected.put("NOT_FOUND", HttpStatus.NOT_FOUND);
        expected.put("METHOD_NOT_ALLOWED", HttpStatus.METHOD_NOT_ALLOWED);
        expected.put("UNSUPPORTED_MEDIA_TYPE", HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        expected.put("INVALID_REQUEST", HttpStatus.BAD_REQUEST);
        expected.put("INTERNAL_SERVER_ERROR", HttpStatus.INTERNAL_SERVER_ERROR);

        Map<String, HttpStatus> actual = new LinkedHashMap<>();
        Arrays.stream(ErrorCode.values()).forEach(c -> actual.put(c.name(), c.getStatus()));

        assertThat(actual).containsExactlyInAnyOrderEntriesOf(expected);
    }

    @Test
    void 에러코드_15개의_응답_문구가_고정되어_있다() {
        Map<ErrorCode, String> expected = new EnumMap<>(ErrorCode.class);
        expected.put(ErrorCode.USER_NOT_FOUND, "사용자를 찾을 수 없습니다.");
        expected.put(ErrorCode.MENU_NOT_FOUND, "메뉴를 찾을 수 없습니다.");
        expected.put(ErrorCode.CART_ITEM_NOT_FOUND, "장바구니 항목을 찾을 수 없습니다.");
        expected.put(ErrorCode.INVALID_QUANTITY, "수량이 올바르지 않습니다.");
        expected.put(ErrorCode.CART_EMPTY, "장바구니가 비어 있습니다.");
        expected.put(ErrorCode.INSUFFICIENT_POINT, "포인트가 부족합니다.");
        expected.put(ErrorCode.INVALID_CHARGE_AMOUNT, "충전 금액이 올바르지 않습니다.");
        expected.put(ErrorCode.CART_UPDATE_CONFLICT, "장바구니가 동시에 수정되었습니다. 다시 시도해 주세요.");
        expected.put(ErrorCode.ORDER_NOT_FOUND, "주문을 찾을 수 없습니다.");
        expected.put(ErrorCode.ALREADY_REFUNDED, "이미 환불된 주문입니다.");
        expected.put(ErrorCode.NOT_FOUND, "요청한 주소를 찾을 수 없습니다.");
        expected.put(ErrorCode.METHOD_NOT_ALLOWED, "지원하지 않는 요청 방식입니다.");
        expected.put(ErrorCode.UNSUPPORTED_MEDIA_TYPE, "지원하지 않는 요청 형식입니다.");
        expected.put(ErrorCode.INVALID_REQUEST, "요청 값이 올바르지 않습니다.");
        expected.put(ErrorCode.INTERNAL_SERVER_ERROR, "서버 오류가 발생했습니다.");

        Map<ErrorCode, String> actual = new EnumMap<>(ErrorCode.class);
        Arrays.stream(ErrorCode.values()).forEach(c -> actual.put(c, c.getMessage()));

        assertThat(actual).containsExactlyInAnyOrderEntriesOf(expected);
    }

    @Test
    void ErrorResponse_는_코드_이름과_상태값_메시지를_그대로_담는다() {
        ErrorResponse response = ErrorResponse.of(ErrorCode.CART_EMPTY);

        assertThat(response.status()).isEqualTo(400);
        assertThat(response.code()).isEqualTo("CART_EMPTY");
        assertThat(response.message()).isEqualTo("장바구니가 비어 있습니다.");
    }
}
