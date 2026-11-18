package com.coffeeorder.domain.point.dto;

import jakarta.validation.constraints.NotNull;

/**
 * 금액 누락·null·소수(1000.5, 1000.0)는 INVALID_REQUEST(소수는 application.yml accept-float-as-int=false),
 * 범위·단위 위반은 서비스에서 INVALID_CHARGE_AMOUNT.
 */
public record ChargeRequest(@NotNull Long amount) {
}
