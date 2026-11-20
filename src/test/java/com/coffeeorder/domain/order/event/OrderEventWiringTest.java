package com.coffeeorder.domain.order.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.coffeeorder.domain.order.event.OrderCompletedEvent.OrderedMenu;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 스프링 안에서 리스너가 빈으로 등록돼 커밋되면 전송·인기 메뉴 반영을 하고 롤백되면 하지 않는지 확인한다(O-8, O-9, P-6).
 * 실행 시점(AFTER_COMMIT, BEFORE_COMMIT 과 구분)은 OrderEventListenerTest·PopularMenuListenerTest 가 고정한다.
 * 이벤트만 발행해 DB 에는 아무것도 쓰지 않는다. 인기 메뉴는 실제 개발 Redis 에 쓰므로
 * 운영 날짜와 겹치지 않는 2099년 키를 쓰고 끝나면 지운다.
 */
@SpringBootTest
class OrderEventWiringTest {

    private static final String KEY = "popular:menu:20990310";

    private final OrderCompletedEvent event = new OrderCompletedEvent(10L, 1L,
            List.of(new OrderedMenu(1L, 2), new OrderedMenu(2L, 1)), 15_000L, LocalDateTime.of(2099, 3, 10, 12, 0));

    @MockitoSpyBean OrderDataSender orderDataSender;
    @Autowired ApplicationEventPublisher eventPublisher;
    @Autowired TransactionTemplate transactionTemplate;
    @Autowired StringRedisTemplate redisTemplate;

    @BeforeEach
    @AfterEach
    void 테스트_키_삭제() {
        redisTemplate.delete(KEY); // 강제 종료로 남은 키가 다음 실행의 점수를 바꾸지 않게 앞뒤로 지운다
    }

    @Test
    void O8_트랜잭션이_커밋되면_주문_데이터를_1번_보내고_인기_메뉴에_수량을_더한다() {
        transactionTemplate.executeWithoutResult(status -> eventPublisher.publishEvent(event));

        verify(orderDataSender, times(1)).send(event);
        assertThat(redisTemplate.opsForZSet().score(KEY, "1")).isEqualTo(2.0);
        assertThat(redisTemplate.opsForZSet().score(KEY, "2")).isEqualTo(1.0);
    }

    @Test
    void 환불이_커밋되면_인기_메뉴에서_빼고_주문_데이터는_다시_보내지_않는다() {
        transactionTemplate.executeWithoutResult(status -> eventPublisher.publishEvent(event));
        reset(orderDataSender);

        transactionTemplate.executeWithoutResult(status -> eventPublisher.publishEvent(
                new OrderRefundedEvent(10L, List.of(new OrderedMenu(1L, 2)), LocalDateTime.of(2099, 3, 10, 12, 0))));

        assertThat(redisTemplate.opsForZSet().score(KEY, "1")).isEqualTo(0.0);
        assertThat(redisTemplate.opsForZSet().score(KEY, "2")).isEqualTo(1.0);
        verifyNoInteractions(orderDataSender); // 환불은 Mock 전송 없음(11/19)
    }

    @Test
    void 환불이_롤백되면_인기_메뉴에서_빼지_않는다() {
        transactionTemplate.executeWithoutResult(status -> eventPublisher.publishEvent(event));
        reset(orderDataSender);

        transactionTemplate.executeWithoutResult(status -> {
            eventPublisher.publishEvent(
                    new OrderRefundedEvent(10L, List.of(new OrderedMenu(1L, 2)), LocalDateTime.of(2099, 3, 10, 12, 0)));
            status.setRollbackOnly();
        });

        assertThat(redisTemplate.opsForZSet().score(KEY, "1")).isEqualTo(2.0);
        assertThat(redisTemplate.opsForZSet().score(KEY, "2")).isEqualTo(1.0);
        verifyNoInteractions(orderDataSender);
    }

    @Test
    void O9_트랜잭션이_롤백되면_보내지도_집계하지도_않는다() {
        transactionTemplate.executeWithoutResult(status -> {
            eventPublisher.publishEvent(event);
            status.setRollbackOnly();
        });

        verify(orderDataSender, never()).send(event);
        assertThat(redisTemplate.hasKey(KEY)).isFalse();
    }
}
