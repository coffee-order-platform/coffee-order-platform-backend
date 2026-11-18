package com.coffeeorder.domain.menu;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class MenuApiTest {

    @Autowired MockMvc mockMvc;

    @Test
    void 메뉴_목록은_시드_5종을_id_오름차순으로_돌려준다() throws Exception {
        MockHttpServletResponse res = mockMvc.perform(get("/api/menus")).andReturn().getResponse();
        String body = res.getContentAsString(UTF_8);

        assertThat(res.getStatus()).isEqualTo(200);
        assertThat(body).startsWith("{\"status\":200,\"message\":\"메뉴 목록 조회 성공\",\"data\":[{\"menuId\":");
        // 테스트 케이스 M-1: 메뉴 5개
        assertThat(body.split("\"menuId\":", -1)).hasSize(6);
        // 메뉴 ID 는 하드코딩하지 않고, 이름·가격·순서만 확인한다
        assertThat(body).containsSubsequence(
                "\"name\":\"아메리카노\",\"price\":4500",
                "\"name\":\"폭신폭신 구름과자 라떼\",\"price\":6000",
                "\"name\":\"졸린 곰돌이 우유\",\"price\":5000",
                "\"name\":\"번개맞은 바닐라\",\"price\":5500",
                "\"name\":\"달빛 두리안 프라푸치노\",\"price\":7000");
    }

    @Test
    void JSON을_받지_않는_요청은_406_JSON으로_응답한다() throws Exception {
        MockHttpServletResponse res = mockMvc.perform(get("/api/menus").accept(MediaType.APPLICATION_XML))
                .andReturn().getResponse();

        assertThat(res.getStatus()).isEqualTo(406);
        assertThat(res.getContentType()).startsWith(MediaType.APPLICATION_JSON_VALUE);
        assertThat(res.getContentAsString(UTF_8)).isEqualTo(
                "{\"status\":406,\"code\":\"NOT_ACCEPTABLE\",\"message\":\"지원하지 않는 응답 형식입니다.\"}");
    }

    @Test
    void POST_는_405_에러_형식으로_응답한다() throws Exception {
        MockHttpServletResponse res = mockMvc.perform(post("/api/menus")).andReturn().getResponse();

        assertThat(res.getStatus()).isEqualTo(405);
        assertThat(res.getContentAsString(UTF_8)).isEqualTo(
                "{\"status\":405,\"code\":\"METHOD_NOT_ALLOWED\",\"message\":\"지원하지 않는 요청 방식입니다.\"}");
        assertThat(res.getHeader("Allow")).contains("GET");
    }
}
