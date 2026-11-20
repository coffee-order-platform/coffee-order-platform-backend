package com.coffeeorder.domain.order.controller;

import com.coffeeorder.domain.order.dto.OrderDetailResponse;
import com.coffeeorder.domain.order.dto.OrderResponse;
import com.coffeeorder.domain.order.dto.OrderSummaryResponse;
import com.coffeeorder.domain.order.service.OrderService;
import com.coffeeorder.global.common.ApiResponse;
import com.coffeeorder.global.common.CursorResponse;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 주문(POST, 장바구니 전체, 새 주문이 생기므로 201 — 노션 5-3), 환불(POST, 201 — 노션 5-5), 주문 조회(GET 목록·단건, 200, message null).
 * produces 고정 이유는 PointController 참고.
 */
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

    /** 최신순 커서. size 는 1~100(기본 10), cursor 는 양수. 범위 밖은 INVALID_REQUEST. */
    @GetMapping
    public ApiResponse<CursorResponse<OrderSummaryResponse>> getOrders(
            @PathVariable Long userId,
            @RequestParam(required = false) @Positive Long cursor,
            @RequestParam(defaultValue = "10") @Min(1) @Max(100) int size) {
        return ApiResponse.ok(orderService.getOrders(userId, cursor, size));
    }

    @GetMapping("/{orderId}")
    public ApiResponse<OrderDetailResponse> getOrder(@PathVariable Long userId, @PathVariable Long orderId) {
        return ApiResponse.ok(orderService.getOrder(userId, orderId));
    }

    /** 전체 환불(노션 5-5). 환불 기록이 새로 생기므로 201, 요청 본문 없음. */
    @PostMapping("/{orderId}/refund")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<OrderResponse> refund(@PathVariable Long userId, @PathVariable Long orderId) {
        return ApiResponse.created("환불이 완료되었습니다.", orderService.refund(userId, orderId));
    }
}
