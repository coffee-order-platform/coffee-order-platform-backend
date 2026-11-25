package com.coffeeorder.domain.cart.service;

import com.coffeeorder.global.exception.BusinessException;
import com.coffeeorder.global.exception.ErrorCode;
import java.sql.SQLException;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Component;

/**
 * 장바구니 동시 수정 재시도 (노션 9-1 락 표, 2026-11-18 결정).
 * - 낙관적 락 충돌: 최신 상태를 다시 읽어 최대 2회 재시도, 그래도 충돌하면 CART_UPDATE_CONFLICT(409)
 * - 같은 메뉴 동시 첫 담기의 UNIQUE 위반: 1회 재시도(다시 시도하면 기존 행에 수량을 더하는 경로를 탄다),
 *   그래도 위반이면 같은 동시 수정 충돌로 보고 CART_UPDATE_CONFLICT(409)
 * - 그 밖의 무결성 위반(외래 키·NOT NULL·다른 UNIQUE): 충돌이 아니므로 재시도·409 변환 없이 그대로 던진다
 * - 교착(MySQL 1213, SQLSTATE 40001): 1회 재시도, 그래도 교착이면 CART_UPDATE_CONFLICT(409) (2026-11-24 결정, 노션 기술 명세 3 락 표).
 *   주문·삭제 직후 같은 메뉴를 동시에 담으면 지워진 행 자리에서 두 INSERT 가 서로를 기다린다. MySQL 은 한쪽을 통째로 되돌리므로
 *   다시 하면 된다(MySQL 매뉴얼 "How to Minimize and Handle Deadlocks"). 잠금 대기 시간 초과(1205)는 이미 오래 기다렸으므로 그대로 던진다
 * 각 시도는 새 트랜잭션이어야 하므로 이 클래스는 트랜잭션 밖(컨트롤러)에서 불린다.
 */
@Slf4j
@Component
public class CartRetryExecutor {

    static final int MAX_OPTIMISTIC_RETRIES = 2;
    static final int MAX_UNIQUE_RETRIES = 1;
    static final int MAX_DEADLOCK_RETRIES = 1;
    private static final String UNIQUE_CONSTRAINT = "uk_cart_items_user_id_menu_id";
    private static final String DEADLOCK_SQL_STATE = "40001";

    public void run(Runnable attempt) {
        int optimisticRetries = 0;
        int uniqueRetries = 0;
        int deadlockRetries = 0;
        while (true) {
            try {
                attempt.run();
                return;
            } catch (OptimisticLockingFailureException e) {
                if (++optimisticRetries > MAX_OPTIMISTIC_RETRIES) {
                    log.warn("장바구니 충돌이 재시도 {}회 뒤에도 계속됨", MAX_OPTIMISTIC_RETRIES);
                    throw new BusinessException(ErrorCode.CART_UPDATE_CONFLICT);
                }
            } catch (DataIntegrityViolationException e) {
                // (사용자, 메뉴) UNIQUE 경합만 재시도한다. 외래 키·NOT NULL 등 다른 위반은 충돌이 아니므로 그대로 던진다
                if (!isCartUniqueViolation(e)) {
                    throw e;
                }
                if (++uniqueRetries > MAX_UNIQUE_RETRIES) {
                    // 원인 예외를 함께 남겨, 나중에 다른 무결성 위반이 생겨도 409 에 가려지지 않게 한다
                    log.warn("장바구니 UNIQUE 경합이 재시도 {}회 뒤에도 계속됨", MAX_UNIQUE_RETRIES, e);
                    throw new BusinessException(ErrorCode.CART_UPDATE_CONFLICT);
                }
            } catch (PessimisticLockingFailureException e) {
                if (!isDeadlock(e)) {
                    throw e;
                }
                if (++deadlockRetries > MAX_DEADLOCK_RETRIES) {
                    log.warn("장바구니 교착이 재시도 {}회 뒤에도 계속됨", MAX_DEADLOCK_RETRIES, e);
                    throw new BusinessException(ErrorCode.CART_UPDATE_CONFLICT);
                }
            }
        }
    }

    private static boolean isDeadlock(PessimisticLockingFailureException e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql) {
                return DEADLOCK_SQL_STATE.equals(sql.getSQLState());
            }
        }
        return false;
    }

    private static boolean isCartUniqueViolation(DataIntegrityViolationException e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof ConstraintViolationException cve) {
                // MySQL 은 "cart_items.uk_..." 처럼 테이블 이름을 붙여 줄 수 있어 끝부분으로 비교한다
                return cve.getConstraintName() != null && cve.getConstraintName().endsWith(UNIQUE_CONSTRAINT);
            }
        }
        return false;
    }
}
