package com.coffeeorder.global.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class CursorResponseTest {

    private static CursorResponse<Long> of(List<Long> fetched, int size) {
        return CursorResponse.of(fetched, size, id -> id);
    }

    @Test
    void 빈_목록이면_다음_페이지가_없고_커서는_null() {
        CursorResponse<Long> response = of(List.of(), 10);

        assertThat(response.content()).isEmpty();
        assertThat(response.hasNext()).isFalse();
        assertThat(response.nextCursor()).isNull();
    }

    @Test
    void size_보다_적게_조회되면_마지막_페이지() {
        CursorResponse<Long> response = of(List.of(5L, 4L, 3L), 10);

        assertThat(response.content()).containsExactly(5L, 4L, 3L);
        assertThat(response.hasNext()).isFalse();
        assertThat(response.nextCursor()).isNull();
    }

    @Test
    void 정확히_size_개면_다음_페이지가_없다() {
        CursorResponse<Long> response = of(List.of(3L, 2L, 1L), 3);

        assertThat(response.content()).containsExactly(3L, 2L, 1L);
        assertThat(response.hasNext()).isFalse();
        assertThat(response.nextCursor()).isNull();
    }

    @Test
    void size_보다_1개_더_조회되면_초과분은_버리고_커서는_응답_마지막_항목이다() {
        CursorResponse<Long> response = of(List.of(9L, 8L, 7L, 6L), 3);

        assertThat(response.content()).containsExactly(9L, 8L, 7L);
        assertThat(response.hasNext()).isTrue();
        assertThat(response.nextCursor()).isEqualTo(7L);
    }

    @Test
    void size_1_경계에서도_계산이_맞다() {
        CursorResponse<Long> response = of(List.of(2L, 1L), 1);

        assertThat(response.content()).containsExactly(2L);
        assertThat(response.hasNext()).isTrue();
        assertThat(response.nextCursor()).isEqualTo(2L);
    }

    @Test
    void size_가_1_미만이면_예외() {
        assertThatThrownBy(() -> of(List.of(1L), 0)).isInstanceOf(IllegalArgumentException.class);
    }
}
