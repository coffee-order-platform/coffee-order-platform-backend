package com.coffeeorder.domain.point.dto;

import com.coffeeorder.domain.order.entity.Order;
import com.coffeeorder.domain.point.entity.PointHistory;
import com.coffeeorder.domain.point.entity.PointHistoryType;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.time.LocalDateTime;

/** orderId 는 사용·환불 내역만 있고 충전은 null 이다. */
@JsonPropertyOrder({"historyId", "type", "amount", "balanceAfter", "orderId", "createdAt"})
public record PointHistoryResponse(
        Long historyId, PointHistoryType type, long amount, long balanceAfter, Long orderId, LocalDateTime createdAt) {

    public static PointHistoryResponse from(PointHistory history) {
        Order order = history.getOrder();
        return new PointHistoryResponse(
                history.getId(),
                history.getType(),
                history.getAmount(),
                history.getBalanceAfter(),
                order == null ? null : order.getId(),
                history.getCreatedAt());
    }
}
