package com.coffeeorder.domain.cart;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.coffeeorder.domain.cart.service.CartRetryExecutor;
import com.coffeeorder.global.exception.BusinessException;
import com.coffeeorder.global.exception.ErrorCode;
import jakarta.persistence.PessimisticLockException;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicInteger;
import org.hibernate.dialect.MySQLDialect;
import org.hibernate.exception.ConstraintViolationException;
import org.hibernate.exception.ConstraintViolationException.ConstraintKind;
import org.hibernate.exception.LockAcquisitionException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.orm.jpa.hibernate.HibernateExceptionTranslator;

/** 재시도 횟수 규칙(노션 9-1): 낙관적 락 충돌 2회 재시도 후 409, 장바구니 UNIQUE 위반만 1회 재시도. 교착 1회 재시도는 11/24 결정(노션 반영 대기). */
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

    /** 스프링이 Hibernate 위반 예외를 감싸 던지는 모양 그대로 만든다. */
    static DataIntegrityViolationException violation(ConstraintKind kind, String constraintName) {
        return new DataIntegrityViolationException("violation",
                new ConstraintViolationException("violation", new SQLException("violation"), kind, constraintName));
    }

    private static DataIntegrityViolationException uniqueViolation() {
        return violation(ConstraintKind.UNIQUE, "cart_items.uk_cart_items_user_id_menu_id");
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

        executor.run(failing(calls, 1, uniqueViolation()));

        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    void UNIQUE_위반이_2번이면_CART_UPDATE_CONFLICT다() {
        AtomicInteger calls = new AtomicInteger();

        assertThatThrownBy(() -> executor.run(
                        failing(calls, 2, uniqueViolation())))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CART_UPDATE_CONFLICT));
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    void 장바구니_UNIQUE_가_아닌_무결성_위반은_재시도하지_않고_그대로_던진다() {
        DataIntegrityViolationException[] others = {
                violation(ConstraintKind.FOREIGN_KEY, "fk_cart_items_menu_id"),
                violation(ConstraintKind.UNIQUE, "uk_points_user_id"),
                violation(ConstraintKind.NOT_NULL, null), // 제약 이름을 못 읽은 위반
                new DataIntegrityViolationException("원인 없음")};
        for (DataIntegrityViolationException other : others) {
            AtomicInteger calls = new AtomicInteger();
            String label = other.getCause() == null ? "원인 없음" : other.getCause().toString();

            assertThatThrownBy(() -> executor.run(failing(calls, 5, other))).as(label).isSameAs(other);
            assertThat(calls.get()).as(label).isEqualTo(1);
        }
    }

    /** 스프링이 MySQL 잠금 실패를 감싸 던지는 모양: CannotAcquireLockException ← Hibernate LockAcquisitionException ← SQLException */
    private static CannotAcquireLockException lockFailure(String sqlState, int mysqlError) {
        return new CannotAcquireLockException("lock",
                new LockAcquisitionException("lock", new SQLException("lock", sqlState, mysqlError)));
    }

    private static CannotAcquireLockException deadlock() {
        return lockFailure("40001", 1213); // Deadlock found when trying to get lock
    }

    @Test
    void 교착은_1번_재시도한다() {
        AtomicInteger calls = new AtomicInteger();

        executor.run(failing(calls, 1, deadlock()));

        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    void 교착이_2번이면_CART_UPDATE_CONFLICT다() {
        AtomicInteger calls = new AtomicInteger();

        assertThatThrownBy(() -> executor.run(failing(calls, 2, deadlock())))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CART_UPDATE_CONFLICT));
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    void 교착이_아닌_잠금_실패는_재시도하지_않고_그대로_던진다() {
        CannotAcquireLockException timeout = lockFailure("HY000", 1205); // Lock wait timeout exceeded: 이미 오래 기다렸다
        AtomicInteger calls = new AtomicInteger();

        assertThatThrownBy(() -> executor.run(failing(calls, 5, timeout))).isSameAs(timeout);
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void 원인을_알_수_없는_잠금_실패는_재시도하지_않는다() {
        CannotAcquireLockException unknown = new CannotAcquireLockException("원인 없음");
        AtomicInteger calls = new AtomicInteger();

        assertThatThrownBy(() -> executor.run(failing(calls, 5, unknown))).isSameAs(unknown);
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void 실제_MySQL_교착_오류를_Hibernate와_스프링이_바꾼_예외도_교착으로_알아본다() {
        // 손으로 만든 예외 모양이 라이브러리를 올린 뒤에도 실제와 같은지 지킨다: MySQL 1213 → Hibernate 변환 → JPA 예외 → 스프링 변환
        RuntimeException hibernate = new MySQLDialect().buildSQLExceptionConversionDelegate().convert(
                new SQLException("Deadlock found when trying to get lock", "40001", 1213), "insert", "insert into cart_items");
        RuntimeException spring = new HibernateExceptionTranslator().translateExceptionIfPossible(
                new PessimisticLockException("insert", hibernate));
        AtomicInteger calls = new AtomicInteger();

        assertThat(spring).isInstanceOf(CannotAcquireLockException.class);
        executor.run(failing(calls, 1, spring));
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
