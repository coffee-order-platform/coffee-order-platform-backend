package com.coffeeorder.domain.order.dto;

import com.coffeeorder.domain.order.entity.OrderItem;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 주문 결과(영수증): 주문 번호, 결제 총액, 결제 후 잔액, 주문 시각(초 단위), 주문 항목 스냅샷.
 * 주문하면 장바구니가 비워져 화면이 주문 내용을 다시 알 길이 없으므로 항목까지 돌려준다(11/19 경화님 결정).
 * 환불 응답도 같은 형태다(노션 5-5, 11/20). 환불에서는 totalPrice 가 돌려준 금액, balance 가 환불 후 잔액이다.
 */
@JsonPropertyOrder({"orderId", "totalPrice", "balance", "orderedAt", "items"})
public record OrderResponse(
        Long orderId,
        long totalPrice,
        long balance,
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss") LocalDateTime orderedAt,
        List<Item> items) {

    @JsonPropertyOrder({"menuId", "menuName", "unitPrice", "quantity", "linePrice"})
    public record Item(Long menuId, String menuName, long unitPrice, int quantity, long linePrice) {

        public static Item from(OrderItem orderItem) {
            return new Item(orderItem.getMenu().getId(), orderItem.getMenuName(), orderItem.getUnitPrice(),
                    orderItem.getQuantity(), orderItem.getLinePrice());
        }
    }
}
