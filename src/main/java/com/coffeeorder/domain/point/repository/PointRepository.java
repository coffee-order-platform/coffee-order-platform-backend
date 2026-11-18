package com.coffeeorder.domain.point.repository;

import com.coffeeorder.domain.point.entity.Point;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PointRepository extends JpaRepository<Point, Long> {

    Optional<Point> findByUserId(Long userId);

    /** 충전·차감·환불처럼 잔액을 바꿀 때 쓴다. 같은 사용자의 요청은 이 행에서 줄을 서서 차례로 처리된다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Point p where p.user.id = :userId")
    Optional<Point> findByUserIdForUpdate(@Param("userId") Long userId);
}
