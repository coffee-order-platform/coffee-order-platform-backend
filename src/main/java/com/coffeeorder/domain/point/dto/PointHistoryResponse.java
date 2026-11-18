package com.coffeeorder.domain.point.dto;

import com.coffeeorder.domain.order.entity.Order;
import com.coffeeorder.domain.point.entity.PointHistory;
import com.coffeeorder.domain.point.entity.PointHistoryType;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.time.LocalDateTime;

/** orderId 는 사용·환불 내역만 있고 충전은 null 이다. createdAt 은 초 단위로 고정한다(2026-11-18 경화님 결정). */
@JsonPropertyOrder({"historyId", "type", "amount", "balanceAfter", "orderId", "createdAt"})
public record PointHistoryResponse(
        Long historyId,
        PointHistoryType type,
        long amount,
        long balanceAfter,
        Long orderId,
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss") LocalDateTime createdAt) {

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
