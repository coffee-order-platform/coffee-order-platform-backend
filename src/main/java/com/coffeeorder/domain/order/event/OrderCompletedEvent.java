package com.coffeeorder.domain.order.event;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 주문 결제가 끝났다는 알림. 커밋 후 처리(주문 데이터 전송, 인기 메뉴 반영)가 받는다.
 * 커밋 뒤에는 엔티티를 지연 로딩할 수 없어 필요한 값만 복사해 담는다.
 * orderedAt 은 인기 메뉴 날짜 키를 정한다(자정 직후에 처리돼도 주문한 날짜에 더한다).
 */
public record OrderCompletedEvent(
        Long orderId, Long userId, List<OrderedMenu> menus, long totalPrice, LocalDateTime orderedAt) {

    /** 주문 항목 하나: 메뉴와 판매 수량. */
    public record OrderedMenu(Long menuId, int quantity) {
    }

    public List<Long> menuIds() {
        return menus.stream().map(OrderedMenu::menuId).toList();
    }
}
