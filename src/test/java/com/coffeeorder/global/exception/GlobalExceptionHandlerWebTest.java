package com.coffeeorder.global.exception;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 실제 Spring Boot 컨텍스트에서 확인한다. standaloneSetup 에는 정적 리소스 핸들러가 없어서
 * 실서버가 던지는 NoResourceFoundException 을 탈 수 없다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class GlobalExceptionHandlerWebTest {

    private static final String NOT_FOUND_JSON =
            "{\"status\":404,\"code\":\"NOT_FOUND\",\"message\":\"요청한 주소를 찾을 수 없습니다.\"}";

    @Autowired
    private MockMvc mockMvc;

    @Test
    void 실서버에서_없는_주소는_NOT_FOUND_JSON() throws Exception {
        MockHttpServletResponse response = mockMvc.perform(get("/nope")).andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(404);
        assertThat(response.getContentAsString(UTF_8)).isEqualTo(NOT_FOUND_JSON);
    }

    @Test
    void Accept_가_text_html_뿐이어도_JSON_으로_응답한다() throws Exception {
        MockHttpServletResponse response = mockMvc
                .perform(get("/nope").accept(MediaType.TEXT_HTML))
                .andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(404);
        assertThat(response.getContentAsString(UTF_8)).isEqualTo(NOT_FOUND_JSON);
    }
}
