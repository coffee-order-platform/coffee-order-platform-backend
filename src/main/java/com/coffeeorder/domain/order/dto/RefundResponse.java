package com.coffeeorder.domain.order.dto;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/** 환불 결과: 주문 번호, 되돌린 금액, 환불 후 잔액(11/19 경화님 결정). */
@JsonPropertyOrder({"orderId", "refundedAmount", "balance"})
public record RefundResponse(Long orderId, long refundedAmount, long balance) {
}
