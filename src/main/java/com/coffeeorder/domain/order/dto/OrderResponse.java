package com.coffeeorder.domain.order.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.time.LocalDateTime;

/** 주문 결과: 주문 번호, 결제 총액, 결제 후 잔액, 주문 시각(초 단위, 내역 조회와 같은 형식). 11/19 경화님 결정. */
@JsonPropertyOrder({"orderId", "totalPrice", "balance", "orderedAt"})
public record OrderResponse(
        Long orderId,
        long totalPrice,
        long balance,
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss") LocalDateTime orderedAt) {
}
