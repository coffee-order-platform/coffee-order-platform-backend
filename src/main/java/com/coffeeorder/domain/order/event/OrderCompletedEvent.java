package com.coffeeorder.domain.order.event;

import java.util.List;

/**
 * 주문 결제가 끝났다는 알림. 커밋 후 처리(주문 데이터 전송)가 받는다.
 * 커밋 뒤에는 엔티티를 지연 로딩할 수 없어 필요한 값만 복사해 담는다.
 */
public record OrderCompletedEvent(Long orderId, Long userId, List<Long> menuIds, long totalPrice) {
}
