package com.coffeeorder.domain.order.event;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 주문 커밋 후 처리. 커밋된 주문만 받으므로 실패한 주문은 전송되지 않는다(테스트 케이스 O-9).
 * 전송이 실패해도 주문은 이미 커밋됐으니 로그만 남긴다(O-10, 아웃박스 재전송은 2주차).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderEventListener {

    private final OrderDataSender orderDataSender;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void sendOrderData(OrderCompletedEvent event) {
        try {
            orderDataSender.send(event);
        } catch (RuntimeException e) {
            log.warn("주문 데이터 전송 실패: orderId={}", event.orderId(), e);
        }
    }
}
