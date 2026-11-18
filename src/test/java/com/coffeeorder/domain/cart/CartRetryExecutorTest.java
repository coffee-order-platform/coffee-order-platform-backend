package com.coffeeorder.domain.cart;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.coffeeorder.domain.cart.service.CartRetryExecutor;
import com.coffeeorder.global.exception.BusinessException;
import com.coffeeorder.global.exception.ErrorCode;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

/** 재시도 횟수 규칙(노션 9-1): 낙관적 락 충돌 2회 재시도 후 409, UNIQUE 위반 1회 재시도. */
class CartRetryExecutorTest {

    private final CartRetryExecutor executor = new CartRetryExecutor();

    private Runnable failing(AtomicInteger calls, int failures, RuntimeException error) {
        return () -> {
            if (calls.incrementAndGet() <= failures) {
                throw error;
            }
        };
    }

    private static ObjectOptimisticLockingFailureException conflict() {
        return new ObjectOptimisticLockingFailureException("CartItem", 1L);
    }

    @Test
    void 충돌이_없으면_한_번만_실행한다() {
        AtomicInteger calls = new AtomicInteger();

        executor.run(failing(calls, 0, conflict()));

        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void 낙관적_락_충돌이_2번이면_세_번째_시도에서_성공한다() {
        AtomicInteger calls = new AtomicInteger();

        executor.run(failing(calls, 2, conflict()));

        assertThat(calls.get()).isEqualTo(3);
    }

    @Test
    void 낙관적_락_충돌이_3번이면_CART_UPDATE_CONFLICT_이고_더_시도하지_않는다() {
        AtomicInteger calls = new AtomicInteger();

        assertThatThrownBy(() -> executor.run(failing(calls, 3, conflict())))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CART_UPDATE_CONFLICT));
        assertThat(calls.get()).isEqualTo(3);
    }

    @Test
    void UNIQUE_위반은_1번_재시도한다() {
        AtomicInteger calls = new AtomicInteger();

        executor.run(failing(calls, 1, new DataIntegrityViolationException("uk_cart_items_user_id_menu_id")));

        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    void UNIQUE_위반이_2번이면_CART_UPDATE_CONFLICT다() {
        AtomicInteger calls = new AtomicInteger();

        assertThatThrownBy(() -> executor.run(
                        failing(calls, 2, new DataIntegrityViolationException("uk_cart_items_user_id_menu_id"))))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CART_UPDATE_CONFLICT));
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    void 업무_예외는_재시도하지_않는다() {
        AtomicInteger calls = new AtomicInteger();

        assertThatThrownBy(() -> executor.run(
                        failing(calls, 5, new BusinessException(ErrorCode.INVALID_QUANTITY))))
                .isInstanceOf(BusinessException.class);
        assertThat(calls.get()).isEqualTo(1);
    }
}
