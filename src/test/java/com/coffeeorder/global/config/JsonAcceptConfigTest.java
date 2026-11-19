package com.coffeeorder.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/** Accept 판정표: JSON 에 적용되는 가장 구체적인 범위의 q 가 0 이거나 q 가 깨졌으면 거절. DB·스프링 없이 확인한다. */
class JsonAcceptConfigTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "application/json;q=0", "application/json;q=0.0", "application/json;charset=UTF-8;q=0",
            "APPLICATION/JSON;Q=0", "application/json ; q=0", "*/*;q=0", "application/*;q=0",
            "application/json;q=0, */*", "*/*, application/json;q=0", "application/*, application/json;q=0",
            "application/*;q=0, */*", "application/json, application/json;q=0", "application/json;q=0, application/json",
            "application/json;q=abc", "application/json;q=2", "application/json;Q=abc", "application/json;Q=2",
            "application/json;Q=-1", "application/json;Q=NaN", "application/xml, application/json;Q=1.5",
            "application/xml;Q=2, application/json"})
    void JSON_을_q0_으로_거절하거나_q_가_깨졌으면_true(String accept) {
        assertThat(JsonAcceptConfig.refusesJson(accept)).isTrue();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {
            "   ", "application/json", "*/*", "application/*", "application/json;q=0.1, */*;q=0",
            "*/*;q=0, application/json", "application/*;q=0, application/json", "*/*;q=0, application/*",
            "application/xml", "application/xml, application/json;q=0.5",
            "application/xml;q=0, application/json", "text/html;q=0, */*", "application/xml;q=0",
            "application/json;Q=1", "application/json;Q=0.5"})
    void JSON_을_받거나_판단할_수_없으면_false(String accept) {
        // application/xml 처럼 JSON 이 없는 경우는 스프링에 맡긴다: produces 가 있는 포인트·장바구니는 실행 전 406,
        // produces 가 없는 메뉴 조회는 응답을 만들 때 406(조회라 바뀌는 데이터 없음)
        assertThat(JsonAcceptConfig.refusesJson(accept)).isFalse();
    }
}
