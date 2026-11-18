package com.coffeeorder.domain.point.dto;

import jakarta.validation.constraints.NotNull;

/**
 * 금액 누락·null·소수(1000.5, 1000.0)·따옴표 숫자("5000")는 INVALID_REQUEST
 * (소수는 application.yml accept-float-as-int=false, 따옴표 숫자는 allow-coercion-of-scalars=false),
 * 범위·단위 위반은 서비스에서 INVALID_CHARGE_AMOUNT.
 */
public record ChargeRequest(@NotNull Long amount) {
}
