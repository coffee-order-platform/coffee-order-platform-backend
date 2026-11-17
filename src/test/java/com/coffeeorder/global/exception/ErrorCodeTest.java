package com.coffeeorder.global.exception;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/** 작업 가이드 PDF 에 확정된 7개 코드의 이름·상태가 바뀌면 실패한다. */
class ErrorCodeTest {

    @Test
    void 에러코드_12개의_이름과_HTTP_상태가_확정값과_같다() {
        Map<String, HttpStatus> expected = new LinkedHashMap<>();
        expected.put("USER_NOT_FOUND", HttpStatus.NOT_FOUND);
        expected.put("MENU_NOT_FOUND", HttpStatus.NOT_FOUND);
        expected.put("CART_ITEM_NOT_FOUND", HttpStatus.NOT_FOUND);
        expected.put("INVALID_QUANTITY", HttpStatus.BAD_REQUEST);
        expected.put("CART_EMPTY", HttpStatus.BAD_REQUEST);
        expected.put("INSUFFICIENT_POINT", HttpStatus.BAD_REQUEST);
        expected.put("INVALID_CHARGE_AMOUNT", HttpStatus.BAD_REQUEST);
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
    void 모든_에러코드는_비어_있지_않은_메시지를_가진다() {
        assertThat(ErrorCode.values())
                .allSatisfy(c -> assertThat(c.getMessage()).isNotBlank());
    }

    @Test
    void ErrorResponse_는_코드_이름과_상태값_메시지를_그대로_담는다() {
        ErrorResponse response = ErrorResponse.of(ErrorCode.CART_EMPTY);

        assertThat(response.status()).isEqualTo(400);
        assertThat(response.code()).isEqualTo("CART_EMPTY");
        assertThat(response.message()).isEqualTo("장바구니가 비어 있습니다.");
    }
}
