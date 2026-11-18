package com.coffeeorder.domain.point.controller;

import com.coffeeorder.domain.point.dto.BalanceResponse;
import com.coffeeorder.domain.point.dto.ChargeRequest;
import com.coffeeorder.domain.point.dto.PointHistoryResponse;
import com.coffeeorder.domain.point.service.PointService;
import com.coffeeorder.global.common.ApiResponse;
import com.coffeeorder.global.common.CursorResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/users/{userId}/points")
@RequiredArgsConstructor
public class PointController {

    private final PointService pointService;

    @PostMapping("/charge")
    public ApiResponse<BalanceResponse> charge(
            @PathVariable Long userId, @Valid @RequestBody ChargeRequest request) {
        return ApiResponse.ok("포인트 충전 성공", pointService.charge(userId, request.amount()));
    }

    @GetMapping
    public ApiResponse<BalanceResponse> getBalance(@PathVariable Long userId) {
        return ApiResponse.ok("포인트 잔액 조회 성공", pointService.getBalance(userId));
    }

    /** size 는 1~100(기본 10), cursor 는 양수. 범위 밖은 INVALID_REQUEST. */
    @GetMapping("/histories")
    public ApiResponse<CursorResponse<PointHistoryResponse>> getHistories(
            @PathVariable Long userId,
            @RequestParam(required = false) @Positive Long cursor,
            @RequestParam(defaultValue = "10") @Min(1) @Max(100) int size) {
        return ApiResponse.ok("포인트 내역 조회 성공", pointService.getHistories(userId, cursor, size));
    }
}
