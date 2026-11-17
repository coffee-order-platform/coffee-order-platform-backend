package com.coffeeorder.global.common;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.util.List;
import java.util.function.Function;

/**
 * 커서 방식 목록 응답. 호출자는 size 가 아니라 size + 1 개를 조회해서 넘긴다.
 * size 범위(1~100) 검증은 컨트롤러 책임이다.
 */
@JsonPropertyOrder({"content", "nextCursor", "hasNext"})
public record CursorResponse<T>(List<T> content, Long nextCursor, boolean hasNext) {

    public static <T> CursorResponse<T> of(List<T> fetched, int size, Function<T, Long> idOf) {
        if (size < 1) {
            throw new IllegalArgumentException("size 는 1 이상이어야 합니다: " + size);
        }
        boolean hasNext = fetched.size() > size;
        List<T> content = List.copyOf(hasNext ? fetched.subList(0, size) : fetched);
        Long nextCursor = hasNext ? idOf.apply(content.get(content.size() - 1)) : null;
        return new CursorResponse<>(content, nextCursor, hasNext);
    }
}
