package com.coffeeorder.domain.order.dto;

import com.coffeeorder.domain.order.entity.Order;
import com.coffeeorder.domain.order.entity.OrderStatus;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.time.LocalDateTime;

/** 주문 목록의 한 줄. 환불할 주문 ID 를 찾는 용도라 항목은 빼고 단건 조회에서 본다(11/20 경화님 결정). */
@JsonPropertyOrder({"orderId", "totalPrice", "status", "orderedAt"})
public record OrderSummaryResponse(
        Long orderId,
        long totalPrice,
        OrderStatus status,
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss") LocalDateTime orderedAt) {

    public static OrderSummaryResponse from(Order order) {
        return new OrderSummaryResponse(order.getId(), order.getTotalPrice(), order.getStatus(), order.getCreatedAt());
    }
}
