package com.coffeeorder.domain.order.dto;

import com.coffeeorder.domain.order.entity.Order;
import com.coffeeorder.domain.order.entity.OrderItem;
import com.coffeeorder.domain.order.entity.OrderStatus;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.time.LocalDateTime;
import java.util.List;

/** 주문 단건: 목록 항목 + 주문 항목 스냅샷(주문 응답과 같은 모양, 담은 순서). */
@JsonPropertyOrder({"orderId", "totalPrice", "status", "orderedAt", "items"})
public record OrderDetailResponse(
        Long orderId,
        long totalPrice,
        OrderStatus status,
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss") LocalDateTime orderedAt,
        List<OrderResponse.Item> items) {

    public static OrderDetailResponse of(Order order, List<OrderItem> items) {
        return new OrderDetailResponse(order.getId(), order.getTotalPrice(), order.getStatus(), order.getCreatedAt(),
                items.stream().map(OrderResponse.Item::from).toList());
    }
}
