package com.coffeeorder.domain.order.event;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 스프링 안에서 리스너가 빈으로 등록돼 커밋되면 전송하고 롤백되면 보내지 않는지 확인한다(O-8, O-9).
 * 실행 시점(AFTER_COMMIT, BEFORE_COMMIT 과 구분)은 OrderEventListenerTest 가 고정한다.
 * 이벤트만 발행하고 DB 에는 아무것도 쓰지 않아, 커밋해도 개발 DB 가 바뀌지 않는다.
 */
@SpringBootTest
class OrderEventWiringTest {

    private final OrderCompletedEvent event = new OrderCompletedEvent(10L, 1L, List.of(1L, 2L), 15_000L);

    @MockitoSpyBean OrderDataSender orderDataSender;
    @Autowired ApplicationEventPublisher eventPublisher;
    @Autowired TransactionTemplate transactionTemplate;

    @Test
    void O8_트랜잭션이_커밋되면_주문_데이터를_1번_보낸다() {
        transactionTemplate.executeWithoutResult(status -> eventPublisher.publishEvent(event));

        verify(orderDataSender, times(1)).send(event);
    }

    @Test
    void O9_트랜잭션이_롤백되면_보내지_않는다() {
        transactionTemplate.executeWithoutResult(status -> {
            eventPublisher.publishEvent(event);
            status.setRollbackOnly();
        });

        verify(orderDataSender, never()).send(event);
    }
}
