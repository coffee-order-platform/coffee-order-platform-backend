package com.coffeeorder.domain.cart.dto;

import jakarta.validation.constraints.NotNull;

/** 누락·null·문자·소수는 INVALID_REQUEST, 수량 범위(1~99) 위반은 서비스에서 INVALID_QUANTITY. */
public record AddCartItemRequest(@NotNull Long menuId, @NotNull Integer quantity) {
}
