package com.coffeeorder;

import static org.assertj.core.api.Assertions.assertThat;

import com.coffeeorder.global.common.CursorResponse;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
class CoffeeOrderApplicationTests {

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void contextLoads() {
    }

    @Test
    void 시각은_숫자_배열이_아니라_ISO_문자열로_직렬화된다() {
        String json = objectMapper.writeValueAsString(LocalDateTime.of(2026, 11, 17, 10, 0));

        assertThat(json).isEqualTo("\"2026-11-17T10:00:00\"");
    }

    @Test
    void 커서_응답_JSON_은_필드_순서가_고정되고_마지막_페이지도_nextCursor_null_을_포함한다() {
        String nextPage = objectMapper.writeValueAsString(CursorResponse.of(List.of(3L, 2L, 1L), 2, id -> id));
        String lastPage = objectMapper.writeValueAsString(CursorResponse.of(List.of(2L, 1L), 2, id -> id));
        String empty = objectMapper.writeValueAsString(CursorResponse.of(List.<Long>of(), 2, id -> id));

        assertThat(nextPage).isEqualTo("{\"content\":[3,2],\"nextCursor\":2,\"hasNext\":true}");
        assertThat(lastPage).isEqualTo("{\"content\":[2,1],\"nextCursor\":null,\"hasNext\":false}");
        assertThat(empty).isEqualTo("{\"content\":[],\"nextCursor\":null,\"hasNext\":false}");
    }
}
