package com.coffeeorder.global.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Collections;
import java.util.List;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Accept 가 JSON 을 q=0(= 받지 않겠다)으로 거절하면 컨트롤러 실행 전에 406 NOT_ACCEPTABLE 로 막는다.
 * 스프링은 q=0 을 거절로 보지 않아, 이 설정이 없으면 충전·담기 등을 실행하고 JSON 으로 응답해 버린다.
 * HTTP 표준(RFC 9110)대로 "받지 않겠다"는 요청은 실행하지 않는다(11/18 결정).
 * 모든 API 가 JSON 만 응답하므로 조회 API 에도 똑같이 적용한다(OPTIONS 는 허용 메서드 안내라 제외).
 * application/xml 처럼 JSON 이 아예 없는 Accept 는 produces 가 있는 컨트롤러(포인트·장바구니)에서 실행 전에 406 이 된다.
 */
@Configuration
public class JsonAcceptConfig implements WebMvcConfigurer {

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
                    throws HttpMediaTypeNotAcceptableException {
                // Accept 가 여러 줄로 오면 스프링은 모두 이어 보므로 여기서도 전부 이어 본다(첫 줄만 보면 우회된다)
                String accept = String.join(",", Collections.list(request.getHeaders(HttpHeaders.ACCEPT)));
                if (handler instanceof HandlerMethod && !HttpMethod.OPTIONS.matches(request.getMethod())
                        && refusesJson(accept)) {
                    throw new HttpMediaTypeNotAcceptableException(List.of(MediaType.APPLICATION_JSON));
                }
                return true;
            }
        });
    }

    /**
     * JSON 에 적용되는 가장 구체적인 범위(application/json, 그다음 application/*, 그다음 모든 형식)의 q 가 0 이면 거절(RFC 9110).
     * 같은 구체도의 범위가 여럿이면(예: "application/json, application/json;q=0") 순서와 상관없이 하나라도 q=0 이면 거절한다.
     */
    static boolean refusesJson(String accept) {
        if (accept == null || accept.isBlank()) {
            return false;
        }
        List<MediaType> ranges;
        try {
            ranges = MediaType.parseMediaTypes(accept);
        } catch (InvalidMediaTypeException e) {
            return false; // 깨진 Accept 는 스프링의 기존 처리에 맡긴다
        }
        List<MediaType> jsonRanges = ranges.stream().filter(range -> range.includes(MediaType.APPLICATION_JSON)).toList();
        int mostSpecific = jsonRanges.stream().mapToInt(JsonAcceptConfig::rank).min().orElse(-1);
        return jsonRanges.stream()
                .anyMatch(range -> rank(range) == mostSpecific && range.getQualityValue() == 0);
    }

    /** 0 = application/json, 1 = application/*, 2 = 모든 형식. 작을수록 구체적이다. */
    private static int rank(MediaType range) {
        return range.isWildcardType() ? 2 : range.isWildcardSubtype() ? 1 : 0;
    }
}
