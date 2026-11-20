package com.coffeeorder.domain.order.repository;

import com.coffeeorder.domain.order.entity.Order;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderRepository extends JpaRepository<Order, Long> {

    List<Order> findByUserIdOrderByIdDesc(Long userId, Limit limit);

    List<Order> findByUserIdAndIdLessThanOrderByIdDesc(Long userId, Long cursor, Limit limit);

    /** 소유자 조건을 함께 건다. 남의 주문도 "없는 주문"과 똑같이 보이게(ORDER_NOT_FOUND). */
    Optional<Order> findByIdAndUserId(Long id, Long userId);
}
