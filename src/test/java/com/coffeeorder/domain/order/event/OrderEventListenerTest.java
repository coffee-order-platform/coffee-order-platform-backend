package com.coffeeorder.domain.order.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** 커밋 후 처리(O-8, O-10). 롤백형 API 테스트에서는 돌지 않아 리스너를 직접 불러 확인한다. DB·스프링 없이 돈다. */
class OrderEventListenerTest {

    private final OrderCompletedEvent event = new OrderCompletedEvent(10L, 1L, List.of(1L, 2L), 15_000L);

    @Test
    void O8_주문_1건에_주문_데이터를_1번_보낸다() {
        List<OrderCompletedEvent> sent = new ArrayList<>();

        new OrderEventListener(sent::add).sendOrderData(event);

        assertThat(sent).containsExactly(event);
    }

    @Test
    void O10_전송이_실패해도_예외를_밖으로_던지지_않는다() {
        OrderEventListener listener = new OrderEventListener(e -> {
            throw new IllegalStateException("전송 서버 다운");
        });

        assertThatCode(() -> listener.sendOrderData(event)).doesNotThrowAnyException();
    }

    @Test
    void 전송은_커밋이_끝난_뒤에만_실행된다() throws Exception {
        // 커밋 전에 보내면 롤백된 주문까지 전송된다(O-9). 실행 시점이 바뀌면 실패한다
        TransactionalEventListener annotation = OrderEventListener.class
                .getMethod("sendOrderData", OrderCompletedEvent.class)
                .getAnnotation(TransactionalEventListener.class);

        assertThat(annotation).isNotNull();
        assertThat(annotation.phase()).isEqualTo(TransactionPhase.AFTER_COMMIT);
    }
}
