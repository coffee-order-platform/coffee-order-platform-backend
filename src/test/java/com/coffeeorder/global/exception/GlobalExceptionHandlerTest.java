package com.coffeeorder.global.exception;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** DB 없이 컨트롤러 + 예외 처리기만 띄워서 응답 JSON 을 검증한다. */
class GlobalExceptionHandlerTest {

    private final MockMvc mockMvc = MockMvcBuilders
            .standaloneSetup(new TestController())
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    private MockHttpServletResponse call(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request).andReturn().getResponse();
    }

    private void assertError(MockHttpServletResponse response, ErrorCode expected) throws Exception {
        assertThat(response.getStatus()).isEqualTo(expected.getStatus().value());
        assertThat(response.getContentAsString(UTF_8))
                .isEqualTo("{\"status\":%d,\"code\":\"%s\",\"message\":\"%s\"}"
                        .formatted(expected.getStatus().value(), expected.name(), expected.getMessage()));
    }

    @Test
    void 업무_예외는_PDF_예시와_같은_JSON_문자열로_응답한다() throws Exception {
        MockHttpServletResponse response = call(get("/test/business"));

        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(response.getContentAsString(UTF_8))
                .isEqualTo("{\"status\":400,\"code\":\"INSUFFICIENT_POINT\",\"message\":\"포인트가 부족합니다.\"}");
    }

    @Test
    void 업무_예외는_에러코드의_HTTP_상태를_따른다() throws Exception {
        assertError(call(get("/test/user")), ErrorCode.USER_NOT_FOUND);
    }

    @Test
    void 필수_필드가_없는_본문은_INVALID_REQUEST() throws Exception {
        assertError(call(post("/test/body").contentType(MediaType.APPLICATION_JSON).content("{}")),
                ErrorCode.INVALID_REQUEST);
    }

    @Test
    void 깨진_JSON_은_INVALID_REQUEST() throws Exception {
        assertError(call(post("/test/body").contentType(MediaType.APPLICATION_JSON).content("{")),
                ErrorCode.INVALID_REQUEST);
    }

    @Test
    void 빈_본문은_INVALID_REQUEST() throws Exception {
        assertError(call(post("/test/body").contentType(MediaType.APPLICATION_JSON)),
                ErrorCode.INVALID_REQUEST);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "101", "-1"})
    void size_범위_밖이면_INVALID_REQUEST(String size) throws Exception {
        assertError(call(get("/test/size").param("size", size)), ErrorCode.INVALID_REQUEST);
    }

    @ParameterizedTest
    @ValueSource(strings = {"1", "100"})
    void size_경계값_1과_100은_통과한다(String size) throws Exception {
        assertThat(call(get("/test/size").param("size", size)).getStatus()).isEqualTo(200);
    }

    @Test
    void 숫자_자리에_문자가_오면_INVALID_REQUEST() throws Exception {
        assertError(call(get("/test/num/abc")), ErrorCode.INVALID_REQUEST);
    }

    @Test
    void 필수_파라미터가_없으면_INVALID_REQUEST() throws Exception {
        assertError(call(get("/test/required")), ErrorCode.INVALID_REQUEST);
    }

    @Test
    void ConstraintViolationException_은_INVALID_REQUEST() throws Exception {
        assertError(call(get("/test/constraint")), ErrorCode.INVALID_REQUEST);
    }

    @Test
    void 없는_주소는_NOT_FOUND() throws Exception {
        assertError(call(get("/test/nope")), ErrorCode.NOT_FOUND);
    }

    @Test
    void 지원하지_않는_요청_방식은_METHOD_NOT_ALLOWED() throws Exception {
        assertError(call(delete("/test/business")), ErrorCode.METHOD_NOT_ALLOWED);
    }

    @Test
    void JSON_이_아닌_본문은_UNSUPPORTED_MEDIA_TYPE() throws Exception {
        assertError(call(post("/test/body").contentType(MediaType.TEXT_PLAIN).content("x")),
                ErrorCode.UNSUPPORTED_MEDIA_TYPE);
    }

    @Test
    void 예상하지_못한_예외는_500_고정_문구이고_내부_메시지를_숨긴다() throws Exception {
        MockHttpServletResponse response = call(get("/test/boom"));

        assertError(response, ErrorCode.INTERNAL_SERVER_ERROR);
        assertThat(response.getContentAsString(UTF_8)).doesNotContain("secret-detail");
    }

    @RestController
    @RequestMapping("/test")
    static class TestController {

        record AmountRequest(@NotNull Long amount) {
        }

        @GetMapping("/business")
        void business() {
            throw new BusinessException(ErrorCode.INSUFFICIENT_POINT);
        }

        @GetMapping("/user")
        void user() {
            throw new BusinessException(ErrorCode.USER_NOT_FOUND);
        }

        @PostMapping(value = "/body", consumes = MediaType.APPLICATION_JSON_VALUE)
        String body(@Valid @RequestBody AmountRequest request) {
            return "ok";
        }

        @GetMapping("/size")
        String size(@RequestParam @Min(1) @Max(100) int size) {
            return "ok";
        }

        @GetMapping("/num/{id}")
        String num(@PathVariable Long id) {
            return "ok";
        }

        @GetMapping("/required")
        String required(@RequestParam String q) {
            return "ok";
        }

        @GetMapping("/constraint")
        void constraint() {
            throw new ConstraintViolationException(Set.of());
        }

        @GetMapping("/boom")
        void boom() {
            throw new IllegalStateException("secret-detail");
        }
    }
}
