package com.coffeeorder;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
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
}
