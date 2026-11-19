package com.coffeeorder.domain.cart.dto;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.util.List;

@JsonPropertyOrder({"items", "totalPrice"})
public record CartResponse(List<CartItemResponse> items, long totalPrice) {

    public static CartResponse of(List<CartItemResponse> items) {
        return new CartResponse(items, items.stream().mapToLong(CartItemResponse::linePrice).sum());
    }
}
