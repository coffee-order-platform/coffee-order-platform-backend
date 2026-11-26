package com.coffeeorder.domain.order.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.coffeeorder.domain.cart.service.CartService;
import com.coffeeorder.domain.menu.repository.PopularMenuRepository;
import com.coffeeorder.domain.order.event.OrderCompletedEvent.OrderedMenu;
import com.coffeeorder.domain.order.service.OrderService;
import com.coffeeorder.domain.point.entity.Point;
import com.coffeeorder.domain.point.repository.PointRepository;
import com.coffeeorder.domain.user.entity.User;
import com.coffeeorder.domain.user.repository.UserRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 커밋 후 처리 실험(노션 EXP-7): 인기 메뉴 반영을 "트랜잭션 안"에서 할 때와 지금처럼 "커밋 후"에 할 때,
 * 실패한 주문이 집계에 들어가는지 비교한다.
 * 실제 주문 코드(OrderService.order)를 바깥 트랜잭션 안에서 부르고, 주문이 끝난 직후 일부러 예외를 던져 전부 롤백시킨다.
 * 비교용 "트랜잭션 안" 방식은 테스트에만 있는 @EventListener 가 따로 정한 날짜 키(2099-01-01)에 기록한다 —
 * 실제 리스너가 쓰는 오늘 날짜 키와 섞이지 않게 해서, 한 번 실행으로 두 방식을 나란히 본다.
 * 커밋이 필요해 테스트 전용 스키마에 쓰고, 만든 데이터와 Redis 키는 끝나고 지운다.
 */
@SpringBootTest
@Import(AfterCommitExperimentTest.InTransactionListenerConfig.class)
class AfterCommitExperimentTest {

    private static final int ORDERS = 10;
    private static final LocalDate IN_TX_DATE = LocalDate.of(2099, 1, 1);
    private static final DateTimeFormatter KEY_DATE = DateTimeFormatter.BASIC_ISO_DATE; // PopularMenuRepository 와 같은 키 형식

    @Autowired OrderService orderService;
    @Autowired CartService cartService;
    @Autowired UserRepository userRepository;
    @Autowired PointRepository pointRepository;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    @Autowired Clock clock;

    private final List<Long> createdUsers = new ArrayList<>();

    @BeforeEach
    @AfterEach
    void 데이터_지우기() {
        for (Long userId : createdUsers) {
            jdbc.update("DELETE FROM point_histories WHERE user_id = ?", userId);
            jdbc.update("DELETE FROM order_items WHERE order_id IN (SELECT id FROM orders WHERE user_id = ?)", userId);
            jdbc.update("DELETE FROM orders WHERE user_id = ?", userId);
            jdbc.update("DELETE FROM cart_items WHERE user_id = ?", userId);
            jdbc.update("DELETE FROM points WHERE user_id = ?", userId);
            jdbc.update("DELETE FROM users WHERE id = ?", userId);
        }
        createdUsers.clear();
        redis.delete(List.of(key(IN_TX_DATE), key(LocalDate.now(clock))));
    }

    @Test
    void 실패한_주문_10건은_트랜잭션_안_방식이면_집계에_남고_커밋_후_방식이면_0이다() {
        long americano = jdbc.queryForObject("SELECT id FROM menus WHERE name = '아메리카노' ORDER BY id LIMIT 1", Long.class);

        for (int i = 0; i < ORDERS; i++) {
            long userId = createUser(10_000);
            cartService.add(userId, americano, 2);
            // 주문 자체는 성공시킨 뒤, 같은 트랜잭션에서 그 다음 단계가 실패한 상황을 만든다 → 주문까지 전부 롤백
            assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
                orderService.order(userId);
                throw new IllegalStateException("주문 뒤 단계에서 실패");
            })).isInstanceOf(IllegalStateException.class);
        }

        long orders = jdbc.queryForObject("SELECT COUNT(*) FROM orders WHERE user_id IN ("
                + createdUsers.stream().map(String::valueOf).collect(Collectors.joining(",")) + ")", Long.class);
        Double inTx = redis.opsForZSet().score(key(IN_TX_DATE), String.valueOf(americano));
        Double afterCommit = redis.opsForZSet().score(key(LocalDate.now(clock)), String.valueOf(americano));
        System.out.printf("[EXP-7] 실패 주문 %d건 | DB 주문 %d건 | 트랜잭션 안 방식 집계 %s잔 | 커밋 후 방식 집계 %s잔%n",
                ORDERS, orders, inTx, afterCommit);

        assertThat(orders).as("롤백돼 DB 에는 주문이 없다").isZero();
        assertThat(inTx).as("트랜잭션 안 방식: 롤백과 상관없이 Redis 에 이미 더해져 남는다").isEqualTo(ORDERS * 2.0);
        assertThat(afterCommit).as("커밋 후 방식(실제 코드): 커밋이 없으니 집계에 0건").isNull();

        // 대조군: 롤백 없이 커밋한 주문 1건은 실제 리스너가 같은 오늘 키에 2잔을 더한다.
        // 그래야 위의 null 이 "리스너가 안 돈다·다른 키에 쓴다"가 아니라 "롤백이라 안 썼다"는 뜻이 된다
        long committedUser = createUser(10_000);
        cartService.add(committedUser, americano, 2);
        orderService.order(committedUser);
        assertThat(redis.opsForZSet().score(key(LocalDate.now(clock)), String.valueOf(americano))).isEqualTo(2.0);
    }

    private long createUser(long balance) {
        Long id = tx.execute(status -> {
            User user = userRepository.save(new User("EXP7테스터"));
            pointRepository.save(new Point(user, balance));
            return user.getId();
        });
        createdUsers.add(id);
        return id;
    }

    private static String key(LocalDate date) {
        return "popular:menu:" + date.format(KEY_DATE);
    }

    /** 비교용: 커밋을 기다리지 않고 이벤트를 받자마자 Redis 에 더하는 방식(실제 코드에는 없다). */
    @TestConfiguration
    static class InTransactionListenerConfig {

        @Bean
        InTransactionListener inTransactionListener(PopularMenuRepository popularMenuRepository) {
            return new InTransactionListener(popularMenuRepository);
        }
    }

    record InTransactionListener(PopularMenuRepository popularMenuRepository) {

        @EventListener
        public void addSales(OrderCompletedEvent event) {
            popularMenuRepository.increase(IN_TX_DATE, event.menus().stream()
                    .collect(Collectors.toMap(OrderedMenu::menuId, OrderedMenu::quantity, Integer::sum)));
        }
    }
}
