package com.coffeeorder.domain.order.controller;

import com.coffeeorder.domain.order.dto.OrderResponse;
import com.coffeeorder.domain.order.service.OrderService;
import com.coffeeorder.global.common.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 요청 본문 없이 장바구니 전체를 주문한다. 새 주문이 생기므로 201(노션 5-3). produces 고정 이유는 PointController 참고. */
@RestController
@RequestMapping(value = "/api/users/{userId}/orders", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<OrderResponse> order(@PathVariable Long userId) {
        return ApiResponse.created("주문이 완료되었습니다.", orderService.order(userId));
    }
}
