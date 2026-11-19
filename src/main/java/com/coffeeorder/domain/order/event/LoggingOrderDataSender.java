package com.coffeeorder.domain.order.event;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/** Mock 전송: 실제로 보내지 않고 보낼 내용을 로그로 남긴다. */
@Slf4j
@Component
public class LoggingOrderDataSender implements OrderDataSender {

    @Override
    public void send(OrderCompletedEvent event) {
        log.info("주문 데이터 전송: orderId={}, userId={}, menuIds={}, amount={}",
                event.orderId(), event.userId(), event.menuIds(), event.totalPrice()); // 주문당 1회: 사용자·메뉴 ID·금액
    }
}
