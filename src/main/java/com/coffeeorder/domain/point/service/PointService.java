package com.coffeeorder.domain.point.service;

import com.coffeeorder.domain.point.dto.BalanceResponse;
import com.coffeeorder.domain.point.dto.PointHistoryResponse;
import com.coffeeorder.domain.point.entity.Point;
import com.coffeeorder.domain.point.entity.PointHistory;
import com.coffeeorder.domain.point.entity.PointHistoryType;
import com.coffeeorder.domain.point.repository.PointHistoryRepository;
import com.coffeeorder.domain.point.repository.PointRepository;
import com.coffeeorder.domain.user.repository.UserRepository;
import com.coffeeorder.global.common.CursorResponse;
import com.coffeeorder.global.exception.BusinessException;
import com.coffeeorder.global.exception.ErrorCode;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PointService {

    static final long MIN_CHARGE = 1_000;
    static final long MAX_CHARGE = 500_000;
    static final long CHARGE_UNIT = 1_000;

    private final UserRepository userRepository;
    private final PointRepository pointRepository;
    private final PointHistoryRepository pointHistoryRepository;

    /** 사용자 확인 → 금액 검증 → 포인트 행을 비관적 락으로 조회 → 잔액 증가 → 충전 내역 저장 (노션 5-1) */
    @Transactional
    public BalanceResponse charge(Long userId, long amount) {
        validateUser(userId);
        if (amount < MIN_CHARGE || amount > MAX_CHARGE || amount % CHARGE_UNIT != 0) {
            throw new BusinessException(ErrorCode.INVALID_CHARGE_AMOUNT);
        }
        Point point = pointRepository.findByUserIdForUpdate(userId)
                .orElseThrow(() -> new IllegalStateException("포인트 행이 없는 사용자: " + userId));
        point.charge(amount);
        pointHistoryRepository.save(
                new PointHistory(point.getUser(), null, PointHistoryType.CHARGE, amount, point.getBalance()));
        return new BalanceResponse(point.getBalance());
    }

    @Transactional(readOnly = true)
    public BalanceResponse getBalance(Long userId) {
        validateUser(userId);
        Point point = pointRepository.findByUserId(userId)
                .orElseThrow(() -> new IllegalStateException("포인트 행이 없는 사용자: " + userId));
        return new BalanceResponse(point.getBalance());
    }

    /** 최신순 커서 조회. size+1 개를 읽어 다음 페이지 여부를 판단한다. 커서는 이 사용자의 내역 id 여야 한다. */
    @Transactional(readOnly = true)
    public CursorResponse<PointHistoryResponse> getHistories(Long userId, Long cursor, int size) {
        validateUser(userId);
        Limit limit = Limit.of(size + 1);
        List<PointHistory> fetched;
        if (cursor == null) {
            fetched = pointHistoryRepository.findByUserIdOrderByIdDesc(userId, limit);
        } else {
            if (!pointHistoryRepository.existsByIdAndUserId(cursor, userId)) {
                throw new BusinessException(ErrorCode.INVALID_REQUEST);
            }
            fetched = pointHistoryRepository.findByUserIdAndIdLessThanOrderByIdDesc(userId, cursor, limit);
        }
        return CursorResponse.of(
                fetched.stream().map(PointHistoryResponse::from).toList(), size, PointHistoryResponse::historyId);
    }

    private void validateUser(Long userId) {
        if (!userRepository.existsById(userId)) {
            throw new BusinessException(ErrorCode.USER_NOT_FOUND);
        }
    }
}
