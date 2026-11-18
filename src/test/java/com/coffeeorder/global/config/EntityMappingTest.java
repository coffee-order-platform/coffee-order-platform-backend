package com.coffeeorder.global.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.coffeeorder.domain.cart.entity.CartItem;
import com.coffeeorder.domain.cart.repository.CartItemRepository;
import com.coffeeorder.domain.menu.entity.Menu;
import com.coffeeorder.domain.menu.repository.MenuRepository;
import com.coffeeorder.domain.order.entity.Order;
import com.coffeeorder.domain.order.entity.OrderItem;
import com.coffeeorder.domain.order.repository.OrderItemRepository;
import com.coffeeorder.domain.order.repository.OrderRepository;
import com.coffeeorder.domain.point.entity.Point;
import com.coffeeorder.domain.point.entity.PointHistory;
import com.coffeeorder.domain.point.entity.PointHistoryType;
import com.coffeeorder.domain.point.repository.PointHistoryRepository;
import com.coffeeorder.domain.point.repository.PointRepository;
import com.coffeeorder.domain.user.entity.User;
import com.coffeeorder.domain.user.repository.UserRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.transaction.annotation.Transactional;

/**
 * 엔티티 7개가 실제 MySQL 에 저장되는지, 시각이 서울 벽시계로 기록되는지, 제약이 걸려 있는지 확인한다.
 * 시각은 JPA 로 되읽지 않고 JdbcTemplate 으로 DB 원본 문자열을 읽는다(JPA 는 시간대 오류를 가린다).
 * ID 는 하드코딩하지 않는다(시드·재생성에 따라 달라짐).
 */
@SpringBootTest
@Transactional
class EntityMappingTest {

    // UTC 로 계산하면 날짜가 하루 어긋나는 경계: UTC 15:00 = 서울 다음날 00:00
    @TestBean(methodName = "fixedClock")
    Clock clock;

    static Clock fixedClock() {
        return Clock.fixed(Instant.parse("2026-11-16T15:00:00Z"), ZoneId.of("Asia/Seoul"));
    }

    @Autowired UserRepository userRepository;
    @Autowired MenuRepository menuRepository;
    @Autowired PointRepository pointRepository;
    @Autowired PointHistoryRepository pointHistoryRepository;
    @Autowired CartItemRepository cartItemRepository;
    @Autowired OrderRepository orderRepository;
    @Autowired OrderItemRepository orderItemRepository;
    @Autowired JdbcTemplate jdbc;

    private String raw(String sql, Object... args) {
        return jdbc.queryForObject(sql, String.class, args);
    }

    @Test
    void BaseEntity_상속_엔티티는_서울_시각으로_created_at_updated_at_이_DB에_저장된다() {
        User user = userRepository.saveAndFlush(new User("테스터"));

        assertThat(raw("SELECT CAST(created_at AS CHAR) FROM users WHERE id = ?", user.getId()))
                .startsWith("2026-11-17 00:00:00");
        assertThat(raw("SELECT CAST(updated_at AS CHAR) FROM users WHERE id = ?", user.getId()))
                .startsWith("2026-11-17 00:00:00");
    }

    @Test
    void BaseEntity_미상속_엔티티도_같은_시계로_created_at_이_저장된다() {
        User user = userRepository.saveAndFlush(new User("테스터"));
        Order order = orderRepository.saveAndFlush(new Order(user, 4_500));
        PointHistory history = pointHistoryRepository.saveAndFlush(
                new PointHistory(user, null, PointHistoryType.CHARGE, 1_000, 1_000));

        assertThat(raw("SELECT CAST(created_at AS CHAR) FROM orders WHERE id = ?", order.getId()))
                .startsWith("2026-11-17 00:00:00");
        assertThat(raw("SELECT CAST(created_at AS CHAR) FROM point_histories WHERE id = ?", history.getId()))
                .startsWith("2026-11-17 00:00:00");
    }

    @Test
    void 일곱_테이블에_저장되고_주문_항목_금액과_사용_내역_연결이_DB_원본과_맞다() {
        User user = userRepository.saveAndFlush(new User("테스터"));
        Menu menu = menuRepository.saveAndFlush(new Menu("테스트 라떼", 5_000));
        pointRepository.saveAndFlush(new Point(user, 10_000));
        cartItemRepository.saveAndFlush(new CartItem(user, menu, 2));
        Order order = orderRepository.saveAndFlush(new Order(user, 10_000));
        OrderItem orderItem = orderItemRepository.saveAndFlush(
                new OrderItem(order, menu, menu.getName(), 5_000, 2, 10_000));
        PointHistory use = pointHistoryRepository.saveAndFlush(
                new PointHistory(user, order, PointHistoryType.USE, 10_000, 0));

        assertThat(raw("SELECT CAST(unit_price AS CHAR) FROM order_items WHERE id = ?", orderItem.getId()))
                .isEqualTo("5000");
        assertThat(raw("SELECT CAST(line_price AS CHAR) FROM order_items WHERE id = ?", orderItem.getId()))
                .isEqualTo("10000");
        assertThat(raw("SELECT type FROM point_histories WHERE id = ?", use.getId())).isEqualTo("USE");
        assertThat(jdbc.queryForObject("SELECT order_id FROM point_histories WHERE id = ?", Long.class, use.getId()))
                .isEqualTo(order.getId());
    }

    @Test
    void 컬럼_타입_길이_기본값이_ERD와_같다() {
        // JPA 가 아니라 information_schema 로 실제 DB 컬럼을 읽는다(@JdbcTypeCode·@ColumnDefault·length 회귀 방지)
        assertThat(columnType("point_histories", "type")).isEqualTo("varchar(10)");
        assertThat(columnType("orders", "status")).isEqualTo("varchar(10)");
        assertThat(columnType("menus", "name")).isEqualTo("varchar(100)");
        assertThat(columnType("order_items", "menu_name")).isEqualTo("varchar(100)");
        assertThat(raw("SELECT is_nullable FROM information_schema.columns WHERE table_schema = DATABASE()"
                + " AND table_name = 'menus' AND column_name = 'name'")).isEqualTo("NO");
        assertThat(raw("SELECT column_default FROM information_schema.columns WHERE table_schema = DATABASE()"
                + " AND table_name = 'points' AND column_name = 'balance'")).isEqualTo("0");
    }

    private String columnType(String table, String column) {
        return raw("SELECT column_type FROM information_schema.columns WHERE table_schema = DATABASE()"
                + " AND table_name = ? AND column_name = ?", table, column);
    }

    @Test
    void 새_주문은_PAID_로_저장된다() {
        User user = userRepository.saveAndFlush(new User("테스터"));
        Order order = orderRepository.saveAndFlush(new Order(user, 4_500));

        assertThat(raw("SELECT status FROM orders WHERE id = ?", order.getId())).isEqualTo("PAID");
    }

    @Test
    void 환불_내역은_REFUND_로_주문과_연결되어_저장된다() {
        // DB 의 CHECK 제약이 REFUND 를 허용하는지까지 확인한다(옛 스키마는 CHARGE·USE 만 허용)
        User user = userRepository.saveAndFlush(new User("테스터"));
        Order order = orderRepository.saveAndFlush(new Order(user, 4_500));
        PointHistory refund = pointHistoryRepository.saveAndFlush(
                new PointHistory(user, order, PointHistoryType.REFUND, 4_500, 4_500));

        assertThat(raw("SELECT type FROM point_histories WHERE id = ?", refund.getId())).isEqualTo("REFUND");
    }

    @Test
    void 충전_내역은_order_id_없이_저장된다() {
        User user = userRepository.saveAndFlush(new User("테스터"));
        PointHistory charge = pointHistoryRepository.saveAndFlush(
                new PointHistory(user, null, PointHistoryType.CHARGE, 1_000, 1_000));

        assertThat(jdbc.queryForObject("SELECT order_id FROM point_histories WHERE id = ?", Long.class, charge.getId()))
                .isNull();
    }

    @Test
    void 장바구니_새_행의_version_은_0이다() {
        User user = userRepository.saveAndFlush(new User("테스터"));
        Menu menu = menuRepository.saveAndFlush(new Menu("테스트 라떼", 5_000));
        CartItem item = cartItemRepository.saveAndFlush(new CartItem(user, menu, 1));

        assertThat(raw("SELECT CAST(version AS CHAR) FROM cart_items WHERE id = ?", item.getId())).isEqualTo("0");
    }

    @Test
    void 같은_사용자가_같은_메뉴를_두_행으로_담으면_UNIQUE_위반이다() {
        User user = userRepository.saveAndFlush(new User("테스터"));
        Menu menu = menuRepository.saveAndFlush(new Menu("테스트 라떼", 5_000));
        cartItemRepository.saveAndFlush(new CartItem(user, menu, 1));

        assertThatThrownBy(() -> cartItemRepository.saveAndFlush(new CartItem(user, menu, 2)))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void 한_사용자에게_포인트_행이_두_개면_UNIQUE_위반이다() {
        User user = userRepository.saveAndFlush(new User("테스터"));
        pointRepository.saveAndFlush(new Point(user, 0));

        assertThatThrownBy(() -> pointRepository.saveAndFlush(new Point(user, 0)))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void 이름_50자는_저장되고_51자는_거부된다() {
        userRepository.saveAndFlush(new User("가".repeat(50)));

        assertThatThrownBy(() -> userRepository.saveAndFlush(new User("가".repeat(51))))
                .isInstanceOf(DataAccessException.class);
    }
}
