package com.coffeeorder.domain.cart.repository;

import com.coffeeorder.domain.cart.entity.CartItem;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CartItemRepository extends JpaRepository<CartItem, Long> {

    Optional<CartItem> findByUserIdAndMenuId(Long userId, Long menuId);

    /**
     * 주문용: 장바구니 행을 잠가, 주문 중 들어온 수량 변경·삭제가 주문 커밋까지 기다리게 한다(노션 기술 명세 3, 11/19).
     * 메뉴는 join 하지 않는다 — join 하면 menus 행까지 잠겨 다른 사용자의 주문이 같은 메뉴에서 줄을 선다.
     * user_id 로 범위를 읽어 MySQL 이 인덱스 틈(갭)도 잠그므로, 바로 옆 구간의 다른 사용자 첫 담기는 주문 커밋까지 잠깐 기다릴 수 있다(D-12).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from CartItem c where c.user.id = :userId order by c.id")
    List<CartItem> findAllByUserIdForUpdate(@Param("userId") Long userId);

    /** 조회용: 메뉴를 함께 읽어 항목마다 메뉴 쿼리가 따로 나가지 않게 한다(N+1 방지). 담은 순서(id 오름차순). */
    @Query("select c from CartItem c join fetch c.menu where c.user.id = :userId order by c.id")
    List<CartItem> findAllWithMenuByUserId(@Param("userId") Long userId);
}
