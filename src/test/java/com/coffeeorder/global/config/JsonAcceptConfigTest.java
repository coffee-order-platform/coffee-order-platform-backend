package com.coffeeorder.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/** Accept 판정표: JSON 에 적용되는 가장 구체적인 범위의 q 가 0 이면 거절(순서와 상관없이). DB·스프링 없이 확인한다. */
class JsonAcceptConfigTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "application/json;q=0", "application/json;q=0.0", "application/json;charset=UTF-8;q=0",
            "APPLICATION/JSON;Q=0", "application/json ; q=0", "*/*;q=0", "application/*;q=0",
            "application/json;q=0, */*", "*/*, application/json;q=0", "application/*, application/json;q=0",
            "application/*;q=0, */*", "application/json, application/json;q=0", "application/json;q=0, application/json"})
    void JSON_을_q0_으로_거절하면_true(String accept) {
        assertThat(JsonAcceptConfig.refusesJson(accept)).isTrue();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {
            "   ", "application/json", "*/*", "application/*", "application/json;q=0.1, */*;q=0",
            "*/*;q=0, application/json", "application/*;q=0, application/json", "*/*;q=0, application/*",
            "application/xml", "application/xml, application/json;q=0.5",
            "application/xml;q=0, application/json", "text/html;q=0, */*", "application/xml;q=0",
            "application/json;q=abc", "application/json;q=2"})
    void JSON_을_받거나_판단할_수_없으면_false(String accept) {
        // application/xml 처럼 JSON 이 없는 경우와 깨진 q 는 스프링 produces 처리에 맡긴다(이미 실행 전 406)
        assertThat(JsonAcceptConfig.refusesJson(accept)).isFalse();
    }
}
