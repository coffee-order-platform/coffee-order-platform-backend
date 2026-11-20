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
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * produces 를 JSON 으로 고정한다. 없으면 Accept 가 JSON 이 아닌 충전 요청이 충전을 먼저 실행(커밋)한 뒤
 * 응답 변환 단계에서 실패해, 클라이언트는 에러를 받고 잔액은 이미 바뀐 상태가 된다.
 */
@RestController
@RequestMapping(value = "/api/users/{userId}/points", produces = MediaType.APPLICATION_JSON_VALUE)
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
        return ApiResponse.ok(pointService.getBalance(userId));
    }

    /** size 는 1~100(기본 10), cursor 는 양수. 범위 밖은 INVALID_REQUEST. */
    @GetMapping("/histories")
    public ApiResponse<CursorResponse<PointHistoryResponse>> getHistories(
            @PathVariable Long userId,
            @RequestParam(required = false) @Positive Long cursor,
            @RequestParam(defaultValue = "10") @Min(1) @Max(100) int size) {
        return ApiResponse.ok(pointService.getHistories(userId, cursor, size));
    }
}
