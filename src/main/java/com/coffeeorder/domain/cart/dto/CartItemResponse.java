package com.coffeeorder.domain.cart.dto;

import com.coffeeorder.domain.cart.entity.CartItem;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/** 가격은 현재 메뉴 가격 기준이다(주문할 때 주문 항목에 스냅샷으로 복사). */
@JsonPropertyOrder({"menuId", "name", "price", "quantity", "linePrice"})
public record CartItemResponse(Long menuId, String name, long price, int quantity, long linePrice) {

    public static CartItemResponse from(CartItem item) {
        long price = item.getMenu().getPrice();
        return new CartItemResponse(
                item.getMenu().getId(), item.getMenu().getName(), price, item.getQuantity(),
                price * item.getQuantity());
    }
}
