package com.coffeeorder.global.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Comparator;
import java.util.List;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Accept 가 JSON 을 q=0(= 받지 않겠다)으로 거절하면 컨트롤러 실행 전에 406 NOT_ACCEPTABLE 로 막는다.
 * 스프링의 produces 비교는 q=0 을 거절로 보지 않아, 충전·담기가 먼저 실행될 수 있기 때문이다(11/18 결정).
 * application/xml 처럼 JSON 이 아예 없는 Accept 는 produces 가 이미 실행 전에 406 으로 막는다.
 */
@Configuration
public class JsonAcceptConfig implements WebMvcConfigurer {

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
                    throws HttpMediaTypeNotAcceptableException {
                if (handler instanceof HandlerMethod && refusesJson(request.getHeader(HttpHeaders.ACCEPT))) {
                    throw new HttpMediaTypeNotAcceptableException(List.of(MediaType.APPLICATION_JSON));
                }
                return true;
            }
        });
    }

    /** JSON 에 적용되는 가장 구체적인 범위(application/json, 그다음 application/*, 그다음 모든 형식)의 q 가 0 이면 거절(RFC 9110). */
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
        return ranges.stream()
                .filter(range -> range.includes(MediaType.APPLICATION_JSON))
                .min(Comparator.comparingInt(range -> range.isWildcardType() ? 2 : range.isWildcardSubtype() ? 1 : 0))
                .map(range -> range.getQualityValue() == 0)
                .orElse(false);
    }
}
