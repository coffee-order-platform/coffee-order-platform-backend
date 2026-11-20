package com.coffeeorder.domain.order.repository;

import com.coffeeorder.domain.order.entity.OrderItem;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderItemRepository extends JpaRepository<OrderItem, Long> {

    /** 저장 순서 = 장바구니에 담은 순서 */
    List<OrderItem> findByOrderIdOrderById(Long orderId);
}
