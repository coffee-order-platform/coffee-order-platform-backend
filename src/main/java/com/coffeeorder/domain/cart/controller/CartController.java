package com.coffeeorder.domain.cart.controller;

import com.coffeeorder.domain.cart.dto.AddCartItemRequest;
import com.coffeeorder.domain.cart.dto.CartResponse;
import com.coffeeorder.domain.cart.dto.UpdateCartItemRequest;
import com.coffeeorder.domain.cart.service.CartRetryExecutor;
import com.coffeeorder.domain.cart.service.CartService;
import com.coffeeorder.global.common.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 담기·수량 변경·삭제는 data 없이 메시지만 준다(2026-11-18 경화님 결정). produces 고정 이유는 PointController 참고. */
@RestController
@RequestMapping(value = "/api/users/{userId}/cart", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class CartController {

    private static final String ADDED = "장바구니에 담겼습니다.";

    private final CartService cartService;
    private final CartRetryExecutor retryExecutor;

    @PostMapping("/items")
    public ApiResponse<Void> add(@PathVariable Long userId, @Valid @RequestBody AddCartItemRequest request) {
        retryExecutor.run(() -> cartService.add(userId, request.menuId(), request.quantity()));
        return ApiResponse.ok(ADDED, null);
    }

    @GetMapping
    public ApiResponse<CartResponse> getCart(@PathVariable Long userId) {
        return ApiResponse.ok("장바구니 조회 성공", cartService.getCart(userId));
    }

    /** 수량 변경도 담기와 같은 문구를 쓴다(경화님 결정). */
    @PatchMapping("/items/{menuId}")
    public ApiResponse<Void> changeQuantity(
            @PathVariable Long userId, @PathVariable Long menuId, @Valid @RequestBody UpdateCartItemRequest request) {
        retryExecutor.run(() -> cartService.changeQuantity(userId, menuId, request.quantity()));
        return ApiResponse.ok(ADDED, null);
    }

    @DeleteMapping("/items/{menuId}")
    public ApiResponse<Void> remove(@PathVariable Long userId, @PathVariable Long menuId) {
        retryExecutor.run(() -> cartService.remove(userId, menuId));
        return ApiResponse.ok("장바구니에서 삭제되었습니다.", null);
    }
}
