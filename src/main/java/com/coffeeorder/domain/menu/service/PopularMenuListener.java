package com.coffeeorder.domain.menu.service;

import com.coffeeorder.domain.menu.repository.PopularMenuRepository;
import com.coffeeorder.domain.order.event.OrderCompletedEvent;
import com.coffeeorder.domain.order.event.OrderCompletedEvent.OrderedMenu;
import com.coffeeorder.domain.order.event.OrderRefundedEvent;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 주문·환불 커밋 후 인기 메뉴의 판매 수량을 더하고 뺀다. 커밋된 것만 받으므로 실패한 주문·환불은 집계에 영향이 없다(테스트 케이스 P-6).
 * Redis 가 실패해도 주문·환불은 이미 커밋됐으니 로그만 남긴다(노션 EXP-8). 그 건의 수량은 반영되지 않는다 —
 * 실패한 후처리를 다시 반영하는 아웃박스는 11/30 이후 Kafka 도입 때(노션 ERD order_outbox, V2 메모 D-13).
 * 주문 데이터 전송과 리스너를 나눠, Redis 가 죽어도 전송은 나간다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PopularMenuListener {

    private final PopularMenuRepository popularMenuRepository;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void addSales(OrderCompletedEvent event) {
        try {
            popularMenuRepository.increase(event.orderedAt().toLocalDate(), quantities(event.menus()));
        } catch (RuntimeException e) {
            log.warn("인기 메뉴 반영 실패: orderId={}", event.orderId(), e);
        }
    }

    /** 환불은 환불한 날이 아니라 주문한 날짜 키에서 뺀다(노션 5-5 6단계). */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void subtractSales(OrderRefundedEvent event) {
        try {
            popularMenuRepository.decrease(event.orderedAt().toLocalDate(), quantities(event.menus()));
        } catch (RuntimeException e) {
            log.warn("인기 메뉴 차감 실패: orderId={}", event.orderId(), e);
        }
    }

    private static Map<Long, Integer> quantities(List<OrderedMenu> menus) {
        return menus.stream().collect(Collectors.toMap(OrderedMenu::menuId, OrderedMenu::quantity, Integer::sum));
    }
}
