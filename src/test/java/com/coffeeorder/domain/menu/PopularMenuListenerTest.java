package com.coffeeorder.domain.menu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.coffeeorder.domain.menu.repository.PopularMenuRepository;
import com.coffeeorder.domain.menu.service.PopularMenuListener;
import com.coffeeorder.domain.order.event.OrderCompletedEvent;
import com.coffeeorder.domain.order.event.OrderCompletedEvent.OrderedMenu;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** 주문 커밋 후 인기 메뉴 반영. 리스너를 직접 불러 확인한다(DB·Redis·스프링 없이). */
class PopularMenuListenerTest {

    private final PopularMenuRepository repository = mock(PopularMenuRepository.class);
    private final PopularMenuListener listener = new PopularMenuListener(repository);

    @Test
    void 주문한_날짜_키에_메뉴별_판매_수량을_더한다() {
        // 자정 직전 주문이 자정 넘어 처리돼도 주문한 날짜에 더한다
        listener.addSales(new OrderCompletedEvent(10L, 1L,
                List.of(new OrderedMenu(1L, 3), new OrderedMenu(2L, 1)), 18_000L, LocalDateTime.of(2099, 3, 10, 23, 59)));

        verify(repository).increase(LocalDate.of(2099, 3, 10), Map.of(1L, 3, 2L, 1));
    }

    @Test
    void Redis_가_실패해도_예외를_밖으로_던지지_않는다() {
        // 주문은 이미 커밋됐으므로 주문 응답이 500 이 되면 안 된다(노션 EXP-8). 연결 실패만이 아니라 시간 초과 등 모든 실패를 삼킨다
        doThrow(new QueryTimeoutException("Redis 응답 시간 초과")).when(repository).increase(any(), anyMap());

        assertThatCode(() -> listener.addSales(new OrderCompletedEvent(10L, 1L,
                List.of(new OrderedMenu(1L, 1)), 4_500L, LocalDateTime.of(2099, 3, 10, 12, 0))))
                .doesNotThrowAnyException();
    }

    @Test
    void 반영은_커밋이_끝난_뒤에만_실행된다() throws Exception {
        TransactionalEventListener annotation = PopularMenuListener.class
                .getMethod("addSales", OrderCompletedEvent.class).getAnnotation(TransactionalEventListener.class);

        assertThat(annotation).isNotNull();
        assertThat(annotation.phase()).isEqualTo(TransactionPhase.AFTER_COMMIT);
    }
}
