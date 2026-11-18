package com.coffeeorder.domain.point.repository;

import com.coffeeorder.domain.point.entity.PointHistory;
import java.util.List;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PointHistoryRepository extends JpaRepository<PointHistory, Long> {

    /** 커서 없이 첫 페이지: 최신순(id 내림차순) */
    List<PointHistory> findByUserIdOrderByIdDesc(Long userId, Limit limit);

    /** 커서 다음 페이지: 커서보다 작은 id 를 최신순으로 */
    List<PointHistory> findByUserIdAndIdLessThanOrderByIdDesc(Long userId, Long cursor, Limit limit);

    boolean existsByIdAndUserId(Long id, Long userId);
}
