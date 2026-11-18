package com.coffeeorder.global.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
class ApiResponseTest {

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void 성공_응답은_status_message_data_순서로_직렬화된다() {
        String json = objectMapper.writeValueAsString(ApiResponse.ok("성공", List.of(1)));

        assertThat(json).isEqualTo("{\"status\":200,\"message\":\"성공\",\"data\":[1]}");
    }

    @Test
    void data가_null이어도_키는_남는다() {
        String json = objectMapper.writeValueAsString(ApiResponse.ok("성공", null));

        assertThat(json).isEqualTo("{\"status\":200,\"message\":\"성공\",\"data\":null}");
    }
}
