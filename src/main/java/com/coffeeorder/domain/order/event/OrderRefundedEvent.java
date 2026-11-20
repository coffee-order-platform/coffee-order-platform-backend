package com.coffeeorder.domain.order.event;

import com.coffeeorder.domain.order.event.OrderCompletedEvent.OrderedMenu;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 환불이 커밋되면 인기 메뉴에서 판매 수량을 뺀다(노션 5-5 6단계). 주문한 날짜 키에서 빼야 하므로 주문 시각을 담는다.
 * 환불은 주문 데이터를 다시 보내지 않는다(Mock 전송 없음, 11/19 결정) — 그래서 주문 완료 이벤트와 따로 둔다.
 */
public record OrderRefundedEvent(Long orderId, List<OrderedMenu> menus, LocalDateTime orderedAt) {
}
