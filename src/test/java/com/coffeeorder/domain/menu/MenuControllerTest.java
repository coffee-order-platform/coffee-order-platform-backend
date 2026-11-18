package com.coffeeorder.domain.menu;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.coffeeorder.domain.menu.controller.MenuController;
import com.coffeeorder.domain.menu.dto.MenuResponse;
import com.coffeeorder.domain.menu.service.MenuService;
import com.coffeeorder.global.exception.GlobalExceptionHandler;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** DB 없이 컨트롤러 응답 모양만 확인한다(메뉴 ID 를 고정해서 JSON 문자열 전체를 비교). */
class MenuControllerTest {

    private final MenuService menuService = mock(MenuService.class);
    private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new MenuController(menuService))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    @Test
    void 메뉴_목록_응답_전체_모양() throws Exception {
        when(menuService.findAll()).thenReturn(List.of(new MenuResponse(1L, "아메리카노", 4500)));

        MockHttpServletResponse res = mockMvc.perform(get("/api/menus")).andReturn().getResponse();

        assertThat(res.getContentAsString(UTF_8)).isEqualTo(
                "{\"status\":200,\"message\":\"메뉴 목록 조회 성공\","
                        + "\"data\":[{\"menuId\":1,\"name\":\"아메리카노\",\"price\":4500}]}");
    }

    @Test
    void 메뉴가_없으면_빈_목록() throws Exception {
        when(menuService.findAll()).thenReturn(List.of());

        MockHttpServletResponse res = mockMvc.perform(get("/api/menus")).andReturn().getResponse();

        assertThat(res.getContentAsString(UTF_8))
                .isEqualTo("{\"status\":200,\"message\":\"메뉴 목록 조회 성공\",\"data\":[]}");
    }

    @Test
    void 서비스가_예외를_던지면_500_에러_형식() throws Exception {
        when(menuService.findAll()).thenThrow(new IllegalStateException("boom"));

        MockHttpServletResponse res = mockMvc.perform(get("/api/menus")).andReturn().getResponse();

        assertThat(res.getStatus()).isEqualTo(500);
        assertThat(res.getContentAsString(UTF_8)).isEqualTo(
                "{\"status\":500,\"code\":\"INTERNAL_SERVER_ERROR\",\"message\":\"서버 오류가 발생했습니다.\"}");
    }
}
