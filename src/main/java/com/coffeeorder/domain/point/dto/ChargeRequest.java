package com.coffeeorder.domain.point.dto;

import jakarta.validation.constraints.NotNull;

/** 금액 누락·null 은 INVALID_REQUEST, 범위·단위 위반은 서비스에서 INVALID_CHARGE_AMOUNT. */
public record ChargeRequest(@NotNull Long amount) {
}
