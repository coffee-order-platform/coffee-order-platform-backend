package com.coffeeorder.domain.cart.repository;

import com.coffeeorder.domain.cart.entity.CartItem;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CartItemRepository extends JpaRepository<CartItem, Long> {

    Optional<CartItem> findByUserIdAndMenuId(Long userId, Long menuId);

    /** 조회용: 메뉴를 함께 읽어 항목마다 메뉴 쿼리가 따로 나가지 않게 한다(N+1 방지). 담은 순서(id 오름차순). */
    @Query("select c from CartItem c join fetch c.menu where c.user.id = :userId order by c.id")
    List<CartItem> findAllWithMenuByUserId(@Param("userId") Long userId);
}
